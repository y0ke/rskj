/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.db;

import co.rsk.panic.PanicProcessor;
import org.ethereum.core.BlockHeaderWrapper;
import org.ethereum.datasource.mapdb.MapDBFactory;
import org.ethereum.datasource.mapdb.Serializers;
import org.ethereum.db.index.ArrayListIndex;
import org.ethereum.db.index.Index;
import org.mapdb.DB;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.ethereum.config.SystemProperties.CONFIG;

/**
 * @author Mikhail Kalinin
 * @since 16.09.2015
 */
public class HeaderStoreImpl implements HeaderStore {

    private static final Logger logger = LoggerFactory.getLogger("blockqueue");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private static final String STORE_NAME = "headerstore";
    private MapDBFactory mapDBFactory;

    private DB db;
    private Map<Long, BlockHeaderWrapper> headers;
    private Index index;

    private boolean initDone = false;
    private final ReentrantLock initLock = new ReentrantLock();
    private final Condition init = initLock.newCondition();

    private final Object mutex = new Object();

    @Override
    public void open() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                initLock.lock();
                try {
                    db = mapDBFactory.createTransactionalDB(dbName());
                    headers = db.hashMapCreate(STORE_NAME)
                            .keySerializer(Serializer.LONG)
                            .valueSerializer(Serializers.BLOCK_HEADER_WRAPPER)
                            .makeOrGet();

                    if(CONFIG.databaseReset()) {
                        headers.clear();
                        db.commit();
                    }

                    index = new ArrayListIndex(headers.keySet());
                    initDone = true;
                    init.signalAll();

                    logger.info("Header store loaded, size [{}]", size());
                } finally {
                    initLock.unlock();
                }
            }
        }).start();
    }

    private String dbName() {
        return String.format("%s/%s", STORE_NAME, STORE_NAME);
    }

    @Override
    public void close() {
        awaitInit();
        db.close();
        initDone = false;
    }

    @Override
    public void add(BlockHeaderWrapper header) {
        awaitInit();

        synchronized (mutex) {
            if (index.contains(header.getNumber())) {
                return;
            }
            headers.put(header.getNumber(), header);
            index.add(header.getNumber());
        }

        dbCommit("add");
    }

    @Override
    public void addBatch(Collection<BlockHeaderWrapper> headers) {
        awaitInit();
        synchronized (mutex) {
            List<Long> numbers = new ArrayList<>(headers.size());
            for (BlockHeaderWrapper b : headers) {
                if(!index.contains(b.getNumber()) &&
                        !numbers.contains(b.getNumber())) {

                    this.headers.put(b.getNumber(), b);
                    numbers.add(b.getNumber());
                }
            }

            index.addAll(numbers);
        }
        dbCommit("addBatch: " + headers.size());
    }

    @Override
    public BlockHeaderWrapper peek() {
        awaitInit();

        synchronized (mutex) {
            if(index.isEmpty()) {
                return null;
            }

            Long idx = index.peek();
            return headers.get(idx);
        }
    }

    @Override
    public BlockHeaderWrapper poll() {
        awaitInit();

        BlockHeaderWrapper header = pollInner();
        dbCommit("poll");
        return header;
    }

    @Override
    public List<BlockHeaderWrapper> pollBatch(int qty) {
        awaitInit();

        if (index.isEmpty()) {
            return Collections.emptyList();
        }

        List<BlockHeaderWrapper> headers = new ArrayList<>(qty > size() ? qty : size());
        while (headers.size() < qty) {
            BlockHeaderWrapper header = pollInner();
            if(header == null) {
                break;
            }
            headers.add(header);
        }

        dbCommit("pollBatch: " + headers.size());

        return headers;
    }

    @Override
    public boolean isEmpty() {
        awaitInit();
        return index.isEmpty();
    }

    @Override
    public int size() {
        awaitInit();
        return index.size();
    }

    @Override
    public void clear() {
        awaitInit();

        headers.clear();
        index.clear();

        dbCommit();
    }

    @Override
    public void drop(byte[] nodeId) {
        awaitInit();

        int i = 0;
        List<Long> removed = new ArrayList<>();

        synchronized (index) {

            for (Long idx : index) {
                BlockHeaderWrapper h = headers.get(idx);
                if (h.sentBy(nodeId)) removed.add(idx);
            }

            headers.keySet().removeAll(removed);
            index.removeAll(removed);
        }

        db.commit();

        if (logger.isDebugEnabled()) {
            if (removed.isEmpty()) {
                logger.debug("0 headers are dropped out");
            } else {
                logger.debug("[{}..{}] headers are dropped out", removed.get(0), removed.get(removed.size() - 1));
            }
        }
    }

    private void dbCommit() {
        dbCommit("");
    }

    private void dbCommit(String info) {
        long s = System.currentTimeMillis();
        db.commit();
        logger.debug("HashStoreImpl: db.commit took " + (System.currentTimeMillis() - s) + " ms (" + info + ") " + Thread.currentThread().getName());
    }

    private void awaitInit() {
        initLock.lock();
        try {
            if(!initDone) {
                init.awaitUninterruptibly();
            }
        } finally {
            initLock.unlock();
        }
    }

    private BlockHeaderWrapper pollInner() {
        synchronized (mutex) {
            if (index.isEmpty()) {
                return null;
            }

            Long idx = index.poll();
            BlockHeaderWrapper header = headers.get(idx);
            headers.remove(idx);

            if (header == null) {
                logger.error("Header for index {} is null", idx);
                panicProcessor.panic("headerstore", String.format("Header for index %d is null", idx));
            }

            return header;
        }
    }

    public void setMapDBFactory(MapDBFactory mapDBFactory) {
        this.mapDBFactory = mapDBFactory;
    }
}
