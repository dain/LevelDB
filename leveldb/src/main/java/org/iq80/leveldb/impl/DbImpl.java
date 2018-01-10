/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBComparator;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.Range;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.Snapshot;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;
import org.iq80.leveldb.impl.Filename.FileInfo;
import org.iq80.leveldb.impl.Filename.FileType;
import org.iq80.leveldb.impl.MemTable.MemTableIterator;
import org.iq80.leveldb.impl.WriteBatchImpl.Handler;
import org.iq80.leveldb.table.BytewiseComparator;
import org.iq80.leveldb.table.CustomUserComparator;
import org.iq80.leveldb.table.FilterPolicy;
import org.iq80.leveldb.table.TableBuilder;
import org.iq80.leveldb.table.UserComparator;
import org.iq80.leveldb.util.DbIterator;
import org.iq80.leveldb.util.MergingIterator;
import org.iq80.leveldb.util.SequentialFile;
import org.iq80.leveldb.util.SequentialFileImpl;
import org.iq80.leveldb.util.Slice;
import org.iq80.leveldb.util.SliceInput;
import org.iq80.leveldb.util.SliceOutput;
import org.iq80.leveldb.util.Slices;
import org.iq80.leveldb.util.Snappy;
import org.iq80.leveldb.util.UnbufferedWritableFile;
import org.iq80.leveldb.util.WritableFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.iq80.leveldb.impl.DbConstants.L0_SLOWDOWN_WRITES_TRIGGER;
import static org.iq80.leveldb.impl.DbConstants.L0_STOP_WRITES_TRIGGER;
import static org.iq80.leveldb.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static org.iq80.leveldb.impl.ValueType.DELETION;
import static org.iq80.leveldb.impl.ValueType.VALUE;
import static org.iq80.leveldb.util.SizeOf.SIZE_OF_INT;
import static org.iq80.leveldb.util.SizeOf.SIZE_OF_LONG;
import static org.iq80.leveldb.util.Slices.readLengthPrefixedBytes;
import static org.iq80.leveldb.util.Slices.writeLengthPrefixedBytes;

// todo make thread safe and concurrent
@SuppressWarnings("AccessingNonPublicFieldOfAnotherObject")
public class DbImpl
        implements DB
{
    private final Options options;
    private final File databaseDir;
    private final TableCache tableCache;
    private final DbLock dbLock;
    private final VersionSet versions;

    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final ReentrantLock mutex = new ReentrantLock();
    private final Condition backgroundCondition = mutex.newCondition();

    private final List<Long> pendingOutputs = new ArrayList<>(); // todo
    private final Deque<WriteBatchInternal> writers = new LinkedList<>();
    private final SnapshotList snapshots = new SnapshotList(mutex);
    private final WriteBatchImpl tmpBatch = new WriteBatchImpl();
    private final Env env;

    private LogWriter log;

    private MemTable memTable;
    private volatile MemTable immutableMemTable;

    private final InternalKeyComparator internalKeyComparator;

    private volatile Throwable backgroundException;
    private final ExecutorService compactionExecutor;
    private Future<?> backgroundCompaction;

    private ManualCompaction manualCompaction;

    private CompactionStats[] stats = new CompactionStats[DbConstants.NUM_LEVELS];

    public DbImpl(Options options, File databaseDir, Env env)
            throws IOException
    {
        this.env = env;
        requireNonNull(options, "options is null");
        requireNonNull(databaseDir, "databaseDir is null");
        this.options = options;

        if (this.options.compressionType() == CompressionType.SNAPPY && !Snappy.available()) {
            // Disable snappy if it's not available.
            this.options.compressionType(CompressionType.NONE);
        }

        this.databaseDir = databaseDir;

        if (this.options.filterPolicy() != null) {
            checkArgument(this.options.filterPolicy() instanceof FilterPolicy, "Filter policy must implement Java interface FilterPolicy");
            this.options.filterPolicy(InternalFilterPolicy.convert(this.options.filterPolicy()));
        }

        //use custom comparator if set
        DBComparator comparator = options.comparator();
        UserComparator userComparator;
        if (comparator != null) {
            userComparator = new CustomUserComparator(comparator);
        }
        else {
            userComparator = new BytewiseComparator();
        }
        internalKeyComparator = new InternalKeyComparator(userComparator);
        memTable = new MemTable(internalKeyComparator);
        immutableMemTable = null;

        ThreadFactory compactionThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("leveldb-compaction-%s")
                .setUncaughtExceptionHandler(new UncaughtExceptionHandler()
                {
                    @Override
                    public void uncaughtException(Thread t, Throwable e)
                    {
                        // todo need a real UncaughtExceptionHandler
                        System.out.printf("%s%n", t);
                        e.printStackTrace();
                    }
                })
                .build();
        compactionExecutor = Executors.newSingleThreadExecutor(compactionThreadFactory);

        // Reserve ten files or so for other uses and give the rest to TableCache.
        int tableCacheSize = options.maxOpenFiles() - 10;
        tableCache = new TableCache(databaseDir, tableCacheSize, new InternalUserComparator(internalKeyComparator), options);

        // create the version set

        // create the database dir if it does not already exist
        databaseDir.mkdirs();
        checkArgument(databaseDir.exists(), "Database directory '%s' does not exist and could not be created", databaseDir);
        checkArgument(databaseDir.isDirectory(), "Database directory '%s' is not a directory", databaseDir);

        for (int i = 0; i < DbConstants.NUM_LEVELS; i++) {
            stats[i] = new CompactionStats();
        }

        mutex.lock();
        try {
            // lock the database dir
            dbLock = new DbLock(new File(databaseDir, Filename.lockFileName()));

            // verify the "current" file
            File currentFile = new File(databaseDir, Filename.currentFileName());
            if (!currentFile.canRead()) {
                checkArgument(options.createIfMissing(), "Database '%s' does not exist and the create if missing option is disabled", databaseDir);
            }
            else {
                checkArgument(!options.errorIfExists(), "Database '%s' exists and the error if exists option is enabled", databaseDir);
            }

            versions = new VersionSet(databaseDir, tableCache, internalKeyComparator, options.allowMmapWrites());

            // load  (and recover) current version
            versions.recover();

            // Recover from all newer log files than the ones named in the
            // descriptor (new log files may have been added by the previous
            // incarnation without registering them in the descriptor).
            //
            // Note that PrevLogNumber() is no longer used, but we pay
            // attention to it in case we are recovering a database
            // produced by an older version of leveldb.
            long minLogNumber = versions.getLogNumber();
            long previousLogNumber = versions.getPrevLogNumber();
            List<File> filenames = Filename.listFiles(databaseDir);

            List<Long> logs = new ArrayList<>();
            for (File filename : filenames) {
                FileInfo fileInfo = Filename.parseFileName(filename);

                if (fileInfo != null &&
                        fileInfo.getFileType() == FileType.LOG &&
                        ((fileInfo.getFileNumber() >= minLogNumber) || (fileInfo.getFileNumber() == previousLogNumber))) {
                    logs.add(fileInfo.getFileNumber());
                }
            }

            // Recover in the order in which the logs were generated
            VersionEdit edit = new VersionEdit();
            Collections.sort(logs);
            for (Long fileNumber : logs) {
                long maxSequence = recoverLogFile(fileNumber, edit);
                if (versions.getLastSequence() < maxSequence) {
                    versions.setLastSequence(maxSequence);
                }
            }

            // open transaction log
            long logFileNumber = versions.getNextFileNumber();
            this.log = Logs.createLogWriter(new File(databaseDir, Filename.logFileName(logFileNumber)), logFileNumber, options.allowMmapWrites());
            edit.setLogNumber(log.getFileNumber());

            // apply recovered edits
            versions.logAndApply(edit, mutex);

            // cleanup unused files
            deleteObsoleteFiles();

            // schedule compactions
            maybeScheduleCompaction();
        }
        finally {
            mutex.unlock();
        }
    }

    @Override
    public void close()
    {
        if (shuttingDown.getAndSet(true)) {
            return;
        }

        mutex.lock();
        try {
            while (backgroundCompaction != null) {
                backgroundCondition.awaitUninterruptibly();
            }
        }
        finally {
            mutex.unlock();
        }

        compactionExecutor.shutdown();
        try {
            compactionExecutor.awaitTermination(1, TimeUnit.DAYS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            versions.destroy();
        }
        catch (IOException ignored) {
        }
        try {
            log.close();
        }
        catch (IOException ignored) {
        }
        tableCache.close();
        dbLock.release();
    }

    @Override
    public String getProperty(String name)
    {
        checkBackgroundException();
        if (!name.startsWith("leveldb.")) {
            return null;
        }
        String key = name.substring("leveldb.".length());
        mutex.lock();
        try {
            Matcher matcher;
            matcher = Pattern.compile("num-files-at-level(\\d+)")
                    .matcher(key);
            if (matcher.matches()) {
                final int level = Integer.valueOf(matcher.group(1));
                return String.valueOf(versions.numberOfFilesInLevel(level));
            }
            matcher = Pattern.compile("stats")
                    .matcher(key);
            if (matcher.matches()) {
                final StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("                               Compactions\n");
                stringBuilder.append("Level  Files Size(MB) Time(sec) Read(MB) Write(MB)\n");
                stringBuilder.append("--------------------------------------------------\n");
                for (int level = 0; level < DbConstants.NUM_LEVELS; level++) {
                    int files = versions.numberOfFilesInLevel(level);
                    if (stats[level].micros > 0 || files > 0) {
                        stringBuilder.append(String.format(
                                "%3d %8d %8.0f %9.0f %8.0f %9.0f\n",
                                level,
                                files,
                                versions.numberOfBytesInLevel(level) / 1048576.0,
                                stats[level].micros / 1e6,
                                stats[level].bytesRead / 1048576.0,
                                stats[level].bytesWritten / 1048576.0));
                    }
                }
                return stringBuilder.toString();
            }
            //TODO implement sstables
            //TODO implement approximate-memory-usage
        }
        finally {
            mutex.unlock();
        }
        return null;
    }

    private void deleteObsoleteFiles()
    {
        checkState(mutex.isHeldByCurrentThread());

        // Make a set of all of the live files
        List<Long> live = new ArrayList<>(this.pendingOutputs);
        for (FileMetaData fileMetaData : versions.getLiveFiles()) {
            live.add(fileMetaData.getNumber());
        }

        for (File file : Filename.listFiles(databaseDir)) {
            FileInfo fileInfo = Filename.parseFileName(file);
            if (fileInfo == null) {
                continue;
            }
            long number = fileInfo.getFileNumber();
            boolean keep = true;
            switch (fileInfo.getFileType()) {
                case LOG:
                    keep = ((number >= versions.getLogNumber()) ||
                            (number == versions.getPrevLogNumber()));
                    break;
                case DESCRIPTOR:
                    // Keep my manifest file, and any newer incarnations'
                    // (in case there is a race that allows other incarnations)
                    keep = (number >= versions.getManifestFileNumber());
                    break;
                case TABLE:
                    keep = live.contains(number);
                    break;
                case TEMP:
                    // Any temp files that are currently being written to must
                    // be recorded in pending_outputs_, which is inserted into "live"
                    keep = live.contains(number);
                    break;
                case CURRENT:
                case DB_LOCK:
                case INFO_LOG:
                    keep = true;
                    break;
            }

            if (!keep) {
                if (fileInfo.getFileType() == FileType.TABLE) {
                    tableCache.evict(number);
                }
                // todo info logging system needed
//                Log(options_.info_log, "Delete type=%d #%lld\n",
//                int(type),
//                        static_cast < unsigned long long>(number));
                file.delete();
            }
        }
    }

    public void flushMemTable()
    {
        mutex.lock();
        try {
            // force compaction
            writeInternal(null, new WriteOptions());

            // todo bg_error code
            while (immutableMemTable != null) {
                backgroundCondition.awaitUninterruptibly();
            }
            checkBackgroundException();
        }
        finally {
            mutex.unlock();
        }
    }

    public void compactRange(int level, Slice start, Slice end)
    {
        checkArgument(level >= 0, "level is negative");
        checkArgument(level + 1 < DbConstants.NUM_LEVELS, "level is greater than or equal to %s", DbConstants.NUM_LEVELS);
        requireNonNull(start, "start is null");
        requireNonNull(end, "end is null");

        mutex.lock();
        try {
            while (this.manualCompaction != null) {
                backgroundCondition.awaitUninterruptibly();
            }
            ManualCompaction manualCompaction = new ManualCompaction(level,
                    new InternalKey(start, SequenceNumber.MAX_SEQUENCE_NUMBER, VALUE),
                    new InternalKey(end, 0, DELETION));
            this.manualCompaction = manualCompaction;

            maybeScheduleCompaction();

            while (this.manualCompaction == manualCompaction) {
                backgroundCondition.awaitUninterruptibly();
            }
        }
        finally {
            mutex.unlock();
        }

    }

    private void maybeScheduleCompaction()
    {
        checkState(mutex.isHeldByCurrentThread());

        if (backgroundCompaction != null) {
            // Already scheduled
        }
        else if (shuttingDown.get()) {
            // DB is being shutdown; no more background compactions
        }
        else if (backgroundException != null) {
            // Already got an error; no more changes
        }
        else if (immutableMemTable == null &&
                manualCompaction == null &&
                !versions.needsCompaction()) {
            // No work to be done
        }
        else {
            backgroundCompaction = compactionExecutor.submit(this::backgroundCall);
        }
    }

    public void checkBackgroundException()
    {
        Throwable e = backgroundException;
        if (e != null) {
            throw new BackgroundProcessingException(e);
        }
    }

    private void backgroundCall()
    {
        mutex.lock();
        try {
            checkState(backgroundCompaction != null, "Compaction was not correctly scheduled");

            try {
                if (!shuttingDown.get() && backgroundException == null) {
                    backgroundCompaction();
                }
            }
            finally {
                backgroundCompaction = null;
            }
            // Previous compaction may have produced too many files in a level,
            // so reschedule another compaction if needed.
            maybeScheduleCompaction();
        }
        catch (DatabaseShutdownException ignored) {
        }
        catch (Throwable throwable) {
            backgroundException = throwable;
            if (throwable instanceof Error) {
                throw (Error) throwable;
            }
        }
        finally {
            try {
                backgroundCondition.signalAll();
            }
            finally {
                mutex.unlock();
            }
        }
    }

    private void backgroundCompaction()
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());

        if (immutableMemTable != null) {
            compactMemTable();
        }

        Compaction compaction;
        InternalKey manualEnd = null;
        boolean isManual = manualCompaction != null;
        if (isManual) {
            ManualCompaction m = this.manualCompaction;
            compaction = versions.compactRange(m.level, m.begin, m.end);
            m.done = compaction == null;
            if (compaction != null) {
                manualEnd = compaction.input(0, compaction.getLevelInputs().size() - 1).getLargest();
            }
        }
        else {
            compaction = versions.pickCompaction();
        }

        if (compaction == null) {
            // no compaction
        }
        else if (!isManual && compaction.isTrivialMove()) {
            // Move file to next level
            checkState(compaction.getLevelInputs().size() == 1);
            FileMetaData fileMetaData = compaction.getLevelInputs().get(0);
            compaction.getEdit().deleteFile(compaction.getLevel(), fileMetaData.getNumber());
            compaction.getEdit().addFile(compaction.getLevel() + 1, fileMetaData);
            versions.logAndApply(compaction.getEdit(), mutex);
            // log
        }
        else {
            CompactionState compactionState = new CompactionState(compaction);
            doCompactionWork(compactionState);
            compaction.close(); //release resources
            cleanupCompaction(compactionState);
            deleteObsoleteFiles();
        }
        if (compaction != null) {
            compaction.close();
        }

        // manual compaction complete
        if (isManual) {
            ManualCompaction m = manualCompaction;
            if (backgroundException != null) {
                m.done = true;
            }
            if (!m.done) {
                m.begin = manualEnd;
            }
            manualCompaction = null;
        }
    }

    private void cleanupCompaction(CompactionState compactionState)
    {
        checkState(mutex.isHeldByCurrentThread());

        if (compactionState.builder != null) {
            compactionState.builder.abandon();
        }
        else {
            checkArgument(compactionState.outfile == null);
        }

        for (FileMetaData output : compactionState.outputs) {
            pendingOutputs.remove(output.getNumber());
        }
    }

    private long recoverLogFile(long fileNumber, VersionEdit edit)
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());
        File file = new File(databaseDir, Filename.logFileName(fileNumber));
        try (SequentialFile in = SequentialFileImpl.open(file);) {
            LogMonitor logMonitor = LogMonitors.logMonitor();
            LogReader logReader = new LogReader(in, logMonitor, true, 0);

            // Log(options_.info_log, "Recovering log #%llu", (unsigned long long) log_number);

            // Read all the records and add to a memtable
            long maxSequence = 0;
            MemTable memTable = null;
            for (Slice record = logReader.readRecord(); record != null; record = logReader.readRecord()) {
                SliceInput sliceInput = record.input();
                // read header
                if (sliceInput.available() < 12) {
                    logMonitor.corruption(sliceInput.available(), "log record too small");
                    continue;
                }
                long sequenceBegin = sliceInput.readLong();
                int updateSize = sliceInput.readInt();

                // read entries
                WriteBatchImpl writeBatch = readWriteBatch(sliceInput, updateSize);

                // apply entries to memTable
                if (memTable == null) {
                    memTable = new MemTable(internalKeyComparator);
                }
                writeBatch.forEach(new InsertIntoHandler(memTable, sequenceBegin));

                // update the maxSequence
                long lastSequence = sequenceBegin + updateSize - 1;
                if (lastSequence > maxSequence) {
                    maxSequence = lastSequence;
                }

                // flush mem table if necessary
                if (memTable.approximateMemoryUsage() > options.writeBufferSize()) {
                    writeLevel0Table(memTable, edit, null);
                    memTable = null;
                }
            }

            // flush mem table
            if (memTable != null && !memTable.isEmpty()) {
                writeLevel0Table(memTable, edit, null);
            }

            return maxSequence;
        }
    }

    @Override
    public byte[] get(byte[] key)
            throws DBException
    {
        return get(key, new ReadOptions());
    }

    @Override
    public byte[] get(byte[] key, ReadOptions options)
            throws DBException
    {
        checkBackgroundException();
        LookupKey lookupKey;
        LookupResult lookupResult;
        mutex.lock();
        try {
            long lastSequence = options.snapshot() != null ?
                    snapshots.getSequenceFrom(options.snapshot()) : versions.getLastSequence();
            lookupKey = new LookupKey(Slices.wrappedBuffer(key), lastSequence);

            // First look in the memtable, then in the immutable memtable (if any).
            final MemTable memTable = this.memTable;
            final MemTable immutableMemTable = this.immutableMemTable;
            final Version current = versions.getCurrent();
            current.retain();
            ReadStats readStats = null;
            mutex.unlock();
            try {
                lookupResult = memTable.get(lookupKey);
                if (lookupResult == null && immutableMemTable != null) {
                    lookupResult = immutableMemTable.get(lookupKey);
                }

                if (lookupResult == null) {
                    // Not in memTables; try live files in level order
                    readStats = new ReadStats();
                    lookupResult = current.get(lookupKey, readStats);
                }

                // schedule compaction if necessary
            }
            finally {
                mutex.lock();
            }
            if (readStats != null && current.updateStats(readStats)) {
                maybeScheduleCompaction();
            }
            current.release();
        }
        finally {
            mutex.unlock();
        }

        if (lookupResult != null) {
            Slice value = lookupResult.getValue();
            if (value != null) {
                return value.getBytes();
            }
        }
        return null;
    }

    @Override
    public void put(byte[] key, byte[] value)
            throws DBException
    {
        put(key, value, new WriteOptions());
    }

    @Override
    public Snapshot put(byte[] key, byte[] value, WriteOptions options)
            throws DBException
    {
        try (WriteBatchImpl writeBatch = new WriteBatchImpl()) {
            return writeInternal(writeBatch.put(key, value), options);
        }
    }

    @Override
    public void delete(byte[] key)
            throws DBException
    {
        try (WriteBatchImpl writeBatch = new WriteBatchImpl()) {
            writeInternal(writeBatch.delete(key), new WriteOptions());
        }
    }

    @Override
    public Snapshot delete(byte[] key, WriteOptions options)
            throws DBException
    {
        try (WriteBatchImpl writeBatch = new WriteBatchImpl()) {
            return writeInternal(writeBatch.delete(key), options);
        }
    }

    @Override
    public void write(WriteBatch updates)
            throws DBException
    {
        writeInternal((WriteBatchImpl) updates, new WriteOptions());
    }

    @Override
    public Snapshot write(WriteBatch updates, WriteOptions options)
            throws DBException
    {
        return writeInternal((WriteBatchImpl) updates, options);
    }

    public Snapshot writeInternal(WriteBatchImpl myBatch, WriteOptions options)
            throws DBException
    {
        checkBackgroundException();
        final WriteBatchInternal w = new WriteBatchInternal(myBatch, options.sync(), mutex.newCondition());
        mutex.lock();
        try {
            writers.offerLast(w);
            while (!w.done && writers.peekFirst() != w) {
                w.await();
            }
            if (w.done) {
                return null;
            }
            long sequenceEnd;
            WriteBatchImpl updates = null;
            ValueHolder<WriteBatchInternal> lastWriter = new ValueHolder<>(w);
            // May temporarily unlock and wait.
            makeRoomForWrite(myBatch == null);
            if (myBatch != null) {
                updates = buildBatchGroup(lastWriter);

                // Get sequence numbers for this change set
                long sequenceBegin = versions.getLastSequence() + 1;
                sequenceEnd = sequenceBegin + updates.size() - 1;

                // Add to log and apply to memtable.  We can release the lock
                // during this phase since "w" is currently responsible for logging
                // and protects against concurrent loggers and concurrent writes
                // into mem_.
                // log and memtable are modified by makeRoomForWrite
                {
                    mutex.unlock();
                    try {
                        // Log write
                        Slice record = writeWriteBatch(updates, sequenceBegin);
                        try {
                            log.addRecord(record, options.sync());
                        }
                        catch (IOException e) {
                            throw new DBException(e);
                        }

                        // Update memtable
                        //this.memTable is modified by makeRoomForWrite
                        updates.forEach(new InsertIntoHandler(this.memTable, sequenceBegin));
                    }
                    finally {
                        mutex.lock();
                    }
                }
                if (updates == tmpBatch) {
                    tmpBatch.clear();
                }
                // Reserve this sequence in the version set
                versions.setLastSequence(sequenceEnd);
            }

            final WriteBatchInternal lastWriteV = lastWriter.getValue();
            while (true) {
                WriteBatchInternal ready = writers.peekFirst();
                writers.pollFirst();
                if (ready != w) {
                    ready.done = true;
                    ready.signal();
                }
                if (ready == lastWriteV) {
                    break;
                }
            }

            // Notify new head of write queue
            if (!writers.isEmpty()) {
                writers.peekFirst().signal();
            }

            if (options.snapshot()) {
                return snapshots.newSnapshot(versions.getLastSequence());
            }
            else {
                return null;
            }
        }
        finally {
            mutex.unlock();
        }
    }

    /**
     * REQUIRES: Writer list must be non-empty
     * REQUIRES: First writer must have a non-NULL batch
     */
    private WriteBatchImpl buildBatchGroup(ValueHolder<WriteBatchInternal> lastWriter)
    {
        checkArgument(!writers.isEmpty(), "A least one writer is required");
        final WriteBatchInternal first = writers.peekFirst();
        WriteBatchImpl result = first.batch;
        checkArgument(result != null, "Batch must be non null");

        int sizeInit;
        sizeInit = first.batch.getApproximateSize();
        /*
         * Allow the group to grow up to a maximum size, but if the
         * original write is small, limit the growth so we do not slow
         * down the small write too much.
         */
        int maxSize = 1 << 20;
        if (sizeInit <= (128 << 10)) {
            maxSize = sizeInit + (128 << 10);
        }

        int size = 0;
        lastWriter.setValue(first);
        for (WriteBatchInternal w : writers) {
            if (w.sync && !lastWriter.getValue().sync) {
                // Do not include a sync write into a batch handled by a non-sync write.
                break;
            }

            if (w.batch != null) {
                size += w.batch.getApproximateSize();
                if (size > maxSize) {
                    // Do not make batch too big
                    break;
                }

                // Append to result
                if (result == first.batch) {
                    // Switch to temporary batch instead of disturbing caller's batch
                    result = tmpBatch;
                    checkState(result.size() == 0, "Temp batch should be clean");
                    result.append(first.batch);
                }
                else if (first.batch != w.batch) {
                    result.append(w.batch);
                }
            }
            lastWriter.setValue(w);
        }
        return result;
    }

    @Override
    public WriteBatch createWriteBatch()
    {
        checkBackgroundException();
        return new WriteBatchImpl();
    }

    @Override
    public SeekingIteratorAdapter iterator()
    {
        return iterator(new ReadOptions());
    }

    @Override
    public SeekingIteratorAdapter iterator(ReadOptions options)
    {
        checkBackgroundException();
        mutex.lock();
        try {
            DbIterator rawIterator = internalIterator();

            // filter any entries not visible in our snapshot
            long snapshot = getSnapshot(options);
            SnapshotSeekingIterator snapshotIterator = new SnapshotSeekingIterator(rawIterator, snapshot, internalKeyComparator.getUserComparator());
            return new SeekingIteratorAdapter(snapshotIterator);
        }
        finally {
            mutex.unlock();
        }
    }

    DbIterator internalIterator()
    {
        mutex.lock();
        try {
            // merge together the memTable, immutableMemTable, and tables in version set
            MemTableIterator iterator = null;
            if (immutableMemTable != null) {
                iterator = immutableMemTable.iterator();
            }
            Version current = versions.getCurrent();
            current.retain();
            return new DbIterator(memTable.iterator(), iterator, current.getLevelIterators(), internalKeyComparator, () -> {
                mutex.lock();
                try {
                    current.release();
                }
                finally {
                    mutex.unlock();
                }
            });
        }
        finally {
            mutex.unlock();
        }
    }

    @Override
    public Snapshot getSnapshot()
    {
        checkBackgroundException();
        mutex.lock();
        try {
            return snapshots.newSnapshot(versions.getLastSequence());
        }
        finally {
            mutex.unlock();
        }
    }

    private long getSnapshot(ReadOptions options)
    {
        long snapshot;
        if (options.snapshot() != null) {
            snapshot = snapshots.getSequenceFrom(options.snapshot());
        }
        else {
            snapshot = versions.getLastSequence();
        }
        return snapshot;
    }

    private void makeRoomForWrite(boolean force)
    {
        checkState(mutex.isHeldByCurrentThread());
        checkState(!writers.isEmpty());

        boolean allowDelay = !force;

        while (true) {
            if (backgroundException != null) {
                throw new DBException("Background exception occurred", backgroundException);
            }
            else if (allowDelay && versions.numberOfFilesInLevel(0) > L0_SLOWDOWN_WRITES_TRIGGER) {
                // We are getting close to hitting a hard limit on the number of
                // L0 files.  Rather than delaying a single write by several
                // seconds when we hit the hard limit, start delaying each
                // individual write by 1ms to reduce latency variance.  Also,
                // this delay hands over some CPU to the compaction thread in
                // case it is sharing the same core as the writer.
                try {
                    mutex.unlock();
                    Thread.sleep(1);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                finally {
                    mutex.lock();
                }

                // Do not delay a single write more than once
                allowDelay = false;
            }
            else if (!force && memTable.approximateMemoryUsage() <= options.writeBufferSize()) {
                // There is room in current memtable
                break;
            }
            else if (immutableMemTable != null) {
                // We have filled up the current memtable, but the previous
                // one is still being compacted, so we wait.
                backgroundCondition.awaitUninterruptibly();
            }
            else if (versions.numberOfFilesInLevel(0) >= L0_STOP_WRITES_TRIGGER) {
                // There are too many level-0 files.
//                Log(options_.info_log, "waiting...\n");
                backgroundCondition.awaitUninterruptibly();
            }
            else {
                // Attempt to switch to a new memtable and trigger compaction of old
                checkState(versions.getPrevLogNumber() == 0);

                // close the existing log
                try {
                    log.close();
                }
                catch (IOException e) {
                    throw new RuntimeException("Unable to close log file " + log, e);
                }

                // open a new log
                long logNumber = versions.getNextFileNumber();
                try {
                    this.log = Logs.createLogWriter(new File(databaseDir, Filename.logFileName(logNumber)), logNumber, options.allowMmapWrites());
                }
                catch (IOException e) {
                    throw new RuntimeException("Unable to open new log file " +
                            new File(databaseDir, Filename.logFileName(logNumber)).getAbsoluteFile(), e);
                }

                // create a new mem table
                immutableMemTable = memTable;
                memTable = new MemTable(internalKeyComparator);

                // Do not force another compaction there is space available
                force = false;

                maybeScheduleCompaction();
            }
        }
    }

    private void compactMemTable()
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());
        checkState(immutableMemTable != null);

        try {
            // Save the contents of the memtable as a new Table
            VersionEdit edit = new VersionEdit();
            Version base = versions.getCurrent();
            base.retain();
            writeLevel0Table(immutableMemTable, edit, base);
            base.release();

            if (shuttingDown.get()) {
                throw new DatabaseShutdownException("Database shutdown during memtable compaction");
            }

            // Replace immutable memtable with the generated Table
            edit.setPreviousLogNumber(0);
            edit.setLogNumber(log.getFileNumber());  // Earlier logs no longer needed
            versions.logAndApply(edit, mutex);

            immutableMemTable = null;
            deleteObsoleteFiles();
        }
        finally {
            backgroundCondition.signalAll();
        }
    }

    private void writeLevel0Table(MemTable mem, VersionEdit edit, Version base)
            throws IOException
    {
        final long startMicros = env.nowMicros();
        checkState(mutex.isHeldByCurrentThread());

        // skip empty mem table
        if (mem.isEmpty()) {
            return;
        }

        // write the memtable to a new sstable
        long fileNumber = versions.getNextFileNumber();
        pendingOutputs.add(fileNumber);
        mutex.unlock();
        FileMetaData meta;
        try {
            meta = buildTable(mem, fileNumber);
        }
        finally {
            mutex.lock();
        }
        pendingOutputs.remove(fileNumber);

        // Note that if file size is zero, the file has been deleted and
        // should not be added to the manifest.
        int level = 0;
        if (meta != null && meta.getFileSize() > 0) {
            Slice minUserKey = meta.getSmallest().getUserKey();
            Slice maxUserKey = meta.getLargest().getUserKey();
            if (base != null) {
                level = base.pickLevelForMemTableOutput(minUserKey, maxUserKey);
            }
            edit.addFile(level, meta);
        }
        this.stats[level].Add(env.nowMicros() - startMicros, 0, meta.getFileSize());
    }

    private FileMetaData buildTable(SeekingIterable<InternalKey, Slice> data, long fileNumber)
            throws IOException
    {
        File file = new File(databaseDir, Filename.tableFileName(fileNumber));
        try {
            InternalKey smallest = null;
            InternalKey largest = null;
            try (WritableFile writableFile = UnbufferedWritableFile.open(file)) {
                TableBuilder tableBuilder = new TableBuilder(options, writableFile, new InternalUserComparator(internalKeyComparator));

                for (Entry<InternalKey, Slice> entry : data) {
                    // update keys
                    InternalKey key = entry.getKey();
                    if (smallest == null) {
                        smallest = key;
                    }
                    largest = key;

                    tableBuilder.add(key.encode(), entry.getValue());
                }

                tableBuilder.finish();
                writableFile.force();
            }

            if (smallest == null) {
                return null;
            }
            FileMetaData fileMetaData = new FileMetaData(fileNumber, file.length(), smallest, largest);

            // verify table can be opened
            tableCache.newIterator(fileMetaData);

            return fileMetaData;

        }
        catch (IOException e) {
            file.delete();
            throw e;
        }
    }

    private void doCompactionWork(CompactionState compactionState)
            throws IOException
    {
        final long startMicros = env.nowMicros();
        long immMicros = 0;  // Micros spent doing imm_ compactions
        checkState(mutex.isHeldByCurrentThread());
        checkArgument(versions.numberOfBytesInLevel(compactionState.getCompaction().getLevel()) > 0);
        checkArgument(compactionState.builder == null);
        checkArgument(compactionState.outfile == null);

        compactionState.smallestSnapshot = snapshots.isEmpty() ? versions.getLastSequence() : snapshots.getOldest();

        // Release mutex while we're actually doing the compaction work
        mutex.unlock();
        try {
            MergingIterator iterator = versions.makeInputIterator(compactionState.compaction);

            Slice currentUserKey = null;
            boolean hasCurrentUserKey = false;

            long lastSequenceForKey = MAX_SEQUENCE_NUMBER;
            while (iterator.hasNext() && !shuttingDown.get()) {
                // always give priority to compacting the current mem table
                if (immutableMemTable != null) {
                    long immStart = env.nowMicros();
                    mutex.lock();
                    try {
                        compactMemTable();
                    }
                    finally {
                        mutex.unlock();
                    }
                    immMicros += (env.nowMicros() - immStart);
                }
                InternalKey key = iterator.peek().getKey();
                if (compactionState.compaction.shouldStopBefore(key) && compactionState.builder != null) {
                    finishCompactionOutputFile(compactionState);
                }

                // Handle key/value, add to state, etc.
                boolean drop = false;
                // todo if key doesn't parse (it is corrupted),
                if (false /*!ParseInternalKey(key, &ikey)*/) {
                    // do not hide error keys
                    currentUserKey = null;
                    hasCurrentUserKey = false;
                    lastSequenceForKey = MAX_SEQUENCE_NUMBER;
                }
                else {
                    if (!hasCurrentUserKey || internalKeyComparator.getUserComparator().compare(key.getUserKey(), currentUserKey) != 0) {
                        // First occurrence of this user key
                        currentUserKey = key.getUserKey();
                        hasCurrentUserKey = true;
                        lastSequenceForKey = MAX_SEQUENCE_NUMBER;
                    }

                    if (lastSequenceForKey <= compactionState.smallestSnapshot) {
                        // Hidden by an newer entry for same user key
                        drop = true; // (A)
                    }
                    else if (key.getValueType() == DELETION &&
                            key.getSequenceNumber() <= compactionState.smallestSnapshot &&
                            compactionState.compaction.isBaseLevelForKey(key.getUserKey())) {
                        // For this user key:
                        // (1) there is no data in higher levels
                        // (2) data in lower levels will have larger sequence numbers
                        // (3) data in layers that are being compacted here and have
                        //     smaller sequence numbers will be dropped in the next
                        //     few iterations of this loop (by rule (A) above).
                        // Therefore this deletion marker is obsolete and can be dropped.
                        drop = true;
                    }

                    lastSequenceForKey = key.getSequenceNumber();
                }

                if (!drop) {
                    // Open output file if necessary
                    if (compactionState.builder == null) {
                        openCompactionOutputFile(compactionState);
                    }
                    if (compactionState.builder.getEntryCount() == 0) {
                        compactionState.currentSmallest = key;
                    }
                    compactionState.currentLargest = key;
                    compactionState.builder.add(key.encode(), iterator.peek().getValue());

                    // Close output file if it is big enough
                    if (compactionState.builder.getFileSize() >=
                            compactionState.compaction.getMaxOutputFileSize()) {
                        finishCompactionOutputFile(compactionState);
                    }
                }
                iterator.next();
            }

            if (shuttingDown.get()) {
                throw new DatabaseShutdownException("DB shutdown during compaction");
            }
            if (compactionState.builder != null) {
                finishCompactionOutputFile(compactionState);
            }
        }
        finally {
            long micros = env.nowMicros() - startMicros - immMicros;
            long bytesRead = 0;
            for (int which = 0; which < 2; which++) {
                for (int i = 0; i < compactionState.compaction.input(which).size(); i++) {
                    bytesRead += compactionState.compaction.input(which, i).getFileSize();
                }
            }
            long bytesWritten = 0;
            for (int i = 0; i < compactionState.outputs.size(); i++) {
                bytesWritten += compactionState.outputs.get(i).getFileSize();
            }
            mutex.lock();
            this.stats[compactionState.compaction.getLevel() + 1].Add(micros, bytesRead, bytesWritten);
        }
        installCompactionResults(compactionState);
    }

    private void openCompactionOutputFile(CompactionState compactionState)
            throws FileNotFoundException
    {
        requireNonNull(compactionState, "compactionState is null");
        checkArgument(compactionState.builder == null, "compactionState builder is not null");

        long fileNumber;
        mutex.lock();
        try {
            fileNumber = versions.getNextFileNumber();
            pendingOutputs.add(fileNumber);
            compactionState.currentFileNumber = fileNumber;
            compactionState.currentFileSize = 0;
            compactionState.currentSmallest = null;
            compactionState.currentLargest = null;
        }
        finally {
            mutex.unlock();
        }
        File file = new File(databaseDir, Filename.tableFileName(fileNumber));
        compactionState.outfile = UnbufferedWritableFile.open(file);
        compactionState.builder = new TableBuilder(options, compactionState.outfile, new InternalUserComparator(internalKeyComparator));
    }

    private void finishCompactionOutputFile(CompactionState compactionState)
            throws IOException
    {
        requireNonNull(compactionState, "compactionState is null");
        checkArgument(compactionState.outfile != null);
        checkArgument(compactionState.builder != null);

        long outputNumber = compactionState.currentFileNumber;
        checkArgument(outputNumber != 0);

        long currentEntries = compactionState.builder.getEntryCount();
        compactionState.builder.finish();

        long currentBytes = compactionState.builder.getFileSize();
        compactionState.currentFileSize = currentBytes;
        compactionState.totalBytes += currentBytes;

        FileMetaData currentFileMetaData = new FileMetaData(compactionState.currentFileNumber,
                compactionState.currentFileSize,
                compactionState.currentSmallest,
                compactionState.currentLargest);
        compactionState.outputs.add(currentFileMetaData);

        compactionState.builder = null;

        compactionState.outfile.force();
        compactionState.outfile.close();
        compactionState.outfile = null;

        if (currentEntries > 0) {
            // Verify that the table is usable
            tableCache.newIterator(outputNumber);
        }
    }

    private void installCompactionResults(CompactionState compact)
            throws IOException
    {
        checkState(mutex.isHeldByCurrentThread());

        // Add compaction outputs
        compact.compaction.addInputDeletions(compact.compaction.getEdit());
        int level = compact.compaction.getLevel();
        for (FileMetaData output : compact.outputs) {
            compact.compaction.getEdit().addFile(level + 1, output);
            pendingOutputs.remove(output.getNumber());
        }

        versions.logAndApply(compact.compaction.getEdit(), mutex);
    }

    @VisibleForTesting
    int numberOfFilesInLevel(int level)
    {
        mutex.lock();
        Version v;
        try {
            v = versions.getCurrent();
        }
        finally {
            mutex.unlock();
        }
        return v.numberOfFilesInLevel(level);
    }

    @Override
    public long[] getApproximateSizes(Range... ranges)
    {
        requireNonNull(ranges, "ranges is null");
        long[] sizes = new long[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            Range range = ranges[i];
            sizes[i] = getApproximateSizes(range);
        }
        return sizes;
    }

    public long getApproximateSizes(Range range)
    {
        mutex.lock();
        Version v;
        try {
            v = versions.getCurrent();
            v.retain();
        }
        finally {
            mutex.unlock();
        }

        InternalKey startKey = new InternalKey(Slices.wrappedBuffer(range.start()), MAX_SEQUENCE_NUMBER, VALUE);
        InternalKey limitKey = new InternalKey(Slices.wrappedBuffer(range.limit()), MAX_SEQUENCE_NUMBER, VALUE);
        long startOffset = v.getApproximateOffsetOf(startKey);
        long limitOffset = v.getApproximateOffsetOf(limitKey);
        mutex.lock();
        try {
            v.release();
        }
        finally {
            mutex.unlock();
        }
        return (limitOffset >= startOffset ? limitOffset - startOffset : 0);
    }

    public long getMaxNextLevelOverlappingBytes()
    {
        mutex.lock();
        try {
            return versions.getMaxNextLevelOverlappingBytes();
        }
        finally {
            mutex.unlock();
        }
    }

    private static class CompactionState
    {
        private final Compaction compaction;

        private final List<FileMetaData> outputs = new ArrayList<>();

        private long smallestSnapshot;

        // State kept for output being generated
        private WritableFile outfile;
        private TableBuilder builder;

        // Current file being generated
        private long currentFileNumber;
        private long currentFileSize;
        private InternalKey currentSmallest;
        private InternalKey currentLargest;

        private long totalBytes;

        private CompactionState(Compaction compaction)
        {
            this.compaction = compaction;
        }

        public Compaction getCompaction()
        {
            return compaction;
        }
    }

    private static class ManualCompaction
    {
        private final int level;
        private InternalKey begin;
        private final InternalKey end;
        private boolean done;

        private ManualCompaction(int level, InternalKey begin, InternalKey end)
        {
            this.level = level;
            this.begin = begin;
            this.end = end;
        }
    }

    // Per level compaction stats.  stats[level] stores the stats for
    // compactions that produced data for the specified "level".
    private static class CompactionStats
    {
        long micros;
        long bytesRead;
        long bytesWritten;

        CompactionStats()
        {
            this.micros = 0;
            this.bytesRead = 0;
            this.bytesWritten = 0;
        }

        public void Add(long micros, long bytesRead, long bytesWritten)
        {
            this.micros += micros;
            this.bytesRead += bytesRead;
            this.bytesWritten += bytesWritten;
        }
    }

    private WriteBatchImpl readWriteBatch(SliceInput record, int updateSize)
            throws IOException
    {
        WriteBatchImpl writeBatch = new WriteBatchImpl();
        int entries = 0;
        while (record.isReadable()) {
            entries++;
            ValueType valueType = ValueType.getValueTypeByPersistentId(record.readByte());
            if (valueType == VALUE) {
                Slice key = readLengthPrefixedBytes(record);
                Slice value = readLengthPrefixedBytes(record);
                writeBatch.put(key, value);
            }
            else if (valueType == DELETION) {
                Slice key = readLengthPrefixedBytes(record);
                writeBatch.delete(key);
            }
            else {
                throw new IllegalStateException("Unexpected value type " + valueType);
            }
        }

        if (entries != updateSize) {
            throw new IOException(String.format("Expected %d entries in log record but found %s entries", updateSize, entries));
        }

        return writeBatch;
    }

    private Slice writeWriteBatch(WriteBatchImpl updates, long sequenceBegin)
    {
        Slice record = Slices.allocate(SIZE_OF_LONG + SIZE_OF_INT + updates.getApproximateSize());
        final SliceOutput sliceOutput = record.output();
        sliceOutput.writeLong(sequenceBegin);
        sliceOutput.writeInt(updates.size());
        updates.forEach(new Handler()
        {
            @Override
            public void put(Slice key, Slice value)
            {
                sliceOutput.writeByte(VALUE.getPersistentId());
                writeLengthPrefixedBytes(sliceOutput, key);
                writeLengthPrefixedBytes(sliceOutput, value);
            }

            @Override
            public void delete(Slice key)
            {
                sliceOutput.writeByte(DELETION.getPersistentId());
                writeLengthPrefixedBytes(sliceOutput, key);
            }
        });
        return record.slice(0, sliceOutput.size());
    }

    public static class DatabaseShutdownException
            extends DBException
    {
        public DatabaseShutdownException()
        {
        }

        public DatabaseShutdownException(String message)
        {
            super(message);
        }
    }

    public static class BackgroundProcessingException
            extends DBException
    {
        public BackgroundProcessingException(Throwable cause)
        {
            super(cause);
        }
    }

    private final Object suspensionMutex = new Object();
    private int suspensionCounter;

    @Override
    public void suspendCompactions()
            throws InterruptedException
    {
        compactionExecutor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                try {
                    synchronized (suspensionMutex) {
                        suspensionCounter++;
                        suspensionMutex.notifyAll();
                        while (suspensionCounter > 0 && !compactionExecutor.isShutdown()) {
                            suspensionMutex.wait(500);
                        }
                    }
                }
                catch (InterruptedException e) {
                }
            }
        });
        synchronized (suspensionMutex) {
            while (suspensionCounter < 1) {
                suspensionMutex.wait();
            }
        }
    }

    @Override
    public void resumeCompactions()
    {
        synchronized (suspensionMutex) {
            suspensionCounter--;
            suspensionMutex.notifyAll();
        }
    }

    @Override
    public void compactRange(byte[] begin, byte[] end)
            throws DBException
    {
        final Slice smallestUserKey = begin == null ? null : new Slice(begin, 0, begin.length);
        final Slice largestUserKey = end == null ? null : new Slice(end, 0, end.length);
        int maxLevelWithFiles = 1;
        mutex.lock();
        try {
            Version base = versions.getCurrent();
            for (int level = 1; level < DbConstants.NUM_LEVELS; level++) {
                if (base.overlapInLevel(level, smallestUserKey, largestUserKey)) {
                    maxLevelWithFiles = level;
                }
            }
        }
        finally {
            mutex.unlock();
        }
        testCompactMemTable(); // TODO: Skip if memtable does not overlap
        for (int level = 0; level < maxLevelWithFiles; level++) {
            testCompactRange(level, smallestUserKey, largestUserKey);
        }
    }

    @VisibleForTesting
    void testCompactRange(int level, Slice begin, Slice end) throws DBException
    {
        checkArgument(level >= 0);
        checkArgument(level + 1 < DbConstants.NUM_LEVELS);

        final InternalKey beginStorage = begin == null ? null : new InternalKey(begin, SequenceNumber.MAX_SEQUENCE_NUMBER, VALUE);
        final InternalKey endStorage = end == null ? null : new InternalKey(end, 0, DELETION);
        ManualCompaction manual = new ManualCompaction(level, beginStorage, endStorage);
        mutex.lock();
        try {
            while (!manual.done && !shuttingDown.get() && backgroundException == null) {
                if (manualCompaction == null) {  // Idle
                    manualCompaction = manual;
                    maybeScheduleCompaction();
                }
                else {  // Running either my compaction or another compaction.
                    backgroundCondition.awaitUninterruptibly();
                }
            }
            if (manualCompaction == manual) {
                // Cancel my manual compaction since we aborted early for some reason.
                manualCompaction = null;
            }
        }
        finally {
            mutex.unlock();
        }
    }

    @VisibleForTesting
    public void testCompactMemTable() throws DBException
    {
        // NULL batch means just wait for earlier writes to be done
        writeInternal(null, new WriteOptions());
        // Wait until the compaction completes
        mutex.lock();

        try {
            while (immutableMemTable != null && backgroundException == null) {
                backgroundCondition.awaitUninterruptibly();
            }
            if (immutableMemTable != null) {
                if (backgroundException != null) {
                    throw new DBException(backgroundException);
                }
            }
        }
        finally {
            mutex.unlock();
        }
    }

    private class WriteBatchInternal
    {
        private final WriteBatchImpl batch;
        private final boolean sync;
        private final Condition backgroundCondition;
        private boolean done = false;

        public WriteBatchInternal(WriteBatchImpl batch, boolean sync, Condition backgroundCondition)
        {
            this.batch = batch;
            this.sync = sync;
            this.backgroundCondition = backgroundCondition;
        }

        public void await()
        {
            backgroundCondition.awaitUninterruptibly();
        }

        public void signal()
        {
            backgroundCondition.signal();
        }
    }
}
