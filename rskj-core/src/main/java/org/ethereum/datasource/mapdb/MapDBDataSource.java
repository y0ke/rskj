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

package org.ethereum.datasource.mapdb;

import org.ethereum.config.SystemProperties;
import org.ethereum.datasource.KeyValueDataSource;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Map;
import java.util.Set;

import static java.lang.System.getProperty;

@Component
@Scope("prototype")
public class MapDBDataSource implements KeyValueDataSource {

    private static final int BATCH_SIZE = 1024 * 1000 * 10;

    @Autowired
    SystemProperties config;

    private DB db;
    private Map<byte[], byte[]> map;
    private String name;
    private boolean alive;

    @Override
    public void init() {
        File dbFile = new File(getProperty("user.dir") + "/" + config.databaseDir() + "/" + name);
        if (!dbFile.getParentFile().exists()) dbFile.getParentFile().mkdirs();


        db = DBMaker.fileDB(dbFile)
                .transactionDisable()
                .closeOnJvmShutdown()
                .make();

        this.map = db.hashMapCreate(name)
                .keySerializer(Serializer.BYTE_ARRAY)
                .valueSerializer(Serializer.BYTE_ARRAY)
                .makeOrGet();

        alive = true;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public byte[] get(byte[] key) {
        return map.get(key);
    }

    @Override
    public byte[] put(byte[] key, byte[] value) {
        try {
            return map.put(key, value);
        } finally {
            db.commit();
        }
    }

    @Override
    public void delete(byte[] key) {
        try {
            map.remove(key);
        } finally {
            db.commit();
        }
    }

    @Override
    public Set<byte[]> keys() {
        return map.keySet();
    }

    @Override
    public void updateBatch(Map<byte[], byte[]> rows) {
        int savedSize = 0;
        try {
            for (byte[] key : rows.keySet()) {
                byte[] value = rows.get(key);
                savedSize += value.length;

                map.put(key, value);
                if (savedSize > BATCH_SIZE) {
                    db.commit();
                    savedSize = 0;
                }
            }
        } finally {
            db.commit();
        }
    }

    @Override
    public void close() {
        db.close();
        alive = false;
    }
}
