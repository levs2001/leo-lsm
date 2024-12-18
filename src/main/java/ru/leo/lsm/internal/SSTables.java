package ru.leo.lsm.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.locks.ReentrantLock;
import ru.leo.lsm.Entry;
import ru.leo.lsm.internal.iterator.IndexedPeekIterator;
import ru.leo.lsm.internal.iterator.TablesIterator;

public final class SSTables implements AutoCloseable {
    private static final String MEM_FILENAME = "daoMem.bin";
    private static final String IND_FILENAME = "daoIndex.bin";
    private static final String COMPACTED_PREFIX = "compacted_";
    private static final String COMPACTED_IND_FILE = COMPACTED_PREFIX + IND_FILENAME;
    private static final String COMPACTED_MEM_FILE = COMPACTED_PREFIX + MEM_FILENAME;
    private static final String TMP_PREFIX = "tmp_";
    private final ReentrantLock flushCompactLock = new ReentrantLock();
    // Order is important, fresh in begin
    private List<SSTable> storageParts;
    private final Path location;

    private SSTables(List<SSTable> storageParts, Path location) {
        this.storageParts = storageParts;
        this.location = location;
    }

    public static SSTables load(Path location) throws IOException {
        Path compactedIndFile = location.resolve(COMPACTED_IND_FILE);
        Path compactedMemFile = location.resolve(COMPACTED_MEM_FILE);
        if (Files.exists(compactedIndFile) || Files.exists(compactedMemFile)) {
            finishCompact(location, compactedIndFile, compactedMemFile);
        }

        ArrayList<SSTable> storageParts = new ArrayList<>();
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            Path nextIndFile = getIndexFilePath(location, i);
            Path nextMemFile = getMemFilePath(location, i);
            try {
                storageParts.add(SSTable.load(nextIndFile, nextMemFile, i));
            } catch (NoSuchFileException e) {
                break;
            }
        }

        // Reverse collection, so fresh is the first
        Collections.reverse(storageParts);
        return new SSTables(storageParts, location);
    }

    /**
     * Finds entry with given key in file.
     *
     * @param key - key for entry to find
     * @return entry with the same key or null if there is no entry with the same key
     */
    public Entry<ByteBuffer> findEntry(ByteBuffer key) {
        Entry<ByteBuffer> res = null;
        for (SSTable storagePart : storageParts) {
            res = storagePart.get(key);
            if (res != null) {
                break;
            }
        }

        return res;
    }

    /**
     * Gives iterator for given range.
     *
     * @param memTables attention: fresh memtables should be on first positions. Order is important
     */
    @SafeVarargs
    public final Iterator<Entry<ByteBuffer>> getMergedEntrys(
        ByteBuffer from, ByteBuffer to, ConcurrentNavigableMap<ByteBuffer, Entry<ByteBuffer>>... memTables) {
        PriorityQueue<IndexedPeekIterator> binaryHeap = new PriorityQueue<>(
            Comparator.comparing(it -> it.peek().key()));

        for (SSTable storagePart : storageParts) {
            IndexedPeekIterator peekIterator = storagePart.get(from, to);
            if (peekIterator.peek() != null) {
                binaryHeap.add(peekIterator);
            }
        }

        int priority = 0;
        for (var memTable : memTables) {
            if (memTable == null) {
                continue;
            }

            IndexedPeekIterator localIter = new IndexedPeekIterator(memTable.values().iterator(),
                Integer.MAX_VALUE - priority);
            if (localIter.peek() != null) {
                binaryHeap.add(localIter);
            }
            priority++;
        }

        return new TablesIterator(binaryHeap);
    }

    public void save(ConcurrentNavigableMap<ByteBuffer, Entry<ByteBuffer>> entrys) throws IOException {
        if (entrys.isEmpty()) {
            return;
        }

        flushCompactLock.lock();
        try {
            Path indPath = getIndexFilePath(storageParts.size());
            Path memPath = getMemFilePath(storageParts.size());
            save(indPath, memPath, entrys.values().iterator());
            // This part of mem is most fresh, so add in begin
            storageParts.addFirst(
                SSTable.load(
                    indPath,
                    memPath,
                    storageParts.size()
                )
            );
        } finally {
            flushCompactLock.unlock();
        }
    }

    public void compact() throws IOException {
        flushCompactLock.lock();
        try {
            Path indCompPath = location.resolve(COMPACTED_IND_FILE);
            Path memCompPath = location.resolve(COMPACTED_MEM_FILE);
            save(indCompPath, memCompPath, getMergedEntrys(null, null));

            // Not correct for windows, because of deleting files
            finishCompact(location, indCompPath, memCompPath);

            // Excluding risk of unvalid storageSystem and setting to compact file:
            List<SSTable> newStParts = new ArrayList<>();
            newStParts.add(SSTable.load(getIndexFilePath(0), getMemFilePath(0), 0));
            storageParts = newStParts;
        } finally {
            flushCompactLock.unlock();
        }
    }

    @Override
    public void close() {
        storageParts.clear();
    }

    public boolean isCompacted() {
        return storageParts.size() <= 1;
    }

    private static void finishCompact(Path location, Path compactedInd, Path compactedMem) throws IOException {
        for (int i = 0; ; i++) {
            Path nextIndFile = getIndexFilePath(location, i);
            Path nextMemFile = getMemFilePath(location, i);

            if (!Files.deleteIfExists(nextIndFile)) {
                break;
            }
            Files.deleteIfExists(nextMemFile);
        }

        Files.move(compactedInd, getIndexFilePath(location, 0), StandardCopyOption.ATOMIC_MOVE);
        Files.move(compactedMem, getMemFilePath(location, 0), StandardCopyOption.ATOMIC_MOVE);
    }

    private Path getMemFilePath(int num) {
        return getMemFilePath(location, num);
    }

    private Path getIndexFilePath(int num) {
        return getIndexFilePath(location, num);
    }

    private static Path getMemFilePath(Path location, int num) {
        return location.resolve(num + MEM_FILENAME);
    }

    private static Path getIndexFilePath(Path location, int num) {
        return location.resolve(num + IND_FILENAME);
    }

    private static void save(Path indPath, Path memPath, Iterator<Entry<ByteBuffer>> entrysToWrite) throws
        IOException {
        Path indTmpPath = indPath.resolveSibling(TMP_PREFIX + indPath.getFileName());
        Files.deleteIfExists(indTmpPath);

        Path memTmpPath = memPath.resolveSibling(TMP_PREFIX + memPath.getFileName());
        Files.deleteIfExists(memTmpPath);

        SSTable.saveSTPart(indTmpPath, memTmpPath, entrysToWrite);
        Files.move(indTmpPath, indPath, StandardCopyOption.ATOMIC_MOVE);
        Files.move(memTmpPath, memPath, StandardCopyOption.ATOMIC_MOVE);
    }
}
