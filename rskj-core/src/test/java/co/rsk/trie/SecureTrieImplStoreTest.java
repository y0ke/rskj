/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
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

package co.rsk.trie;

import org.ethereum.datasource.HashMapDB;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * Created by ajlopez on 03/04/2017.
 */
public class SecureTrieImplStoreTest {
    @Test
    public void saveTrieNode() {
        HashMapDB map = new HashMapDB();
        TrieStoreImpl store = new TrieStoreImpl(map);

        Trie trie = new TrieImpl(store, true).put("foo", "bar".getBytes());

        store.save(trie);

        Assert.assertEquals(1, map.keys().size());
        Assert.assertNotNull(map.get(trie.getHash()));
        Assert.assertArrayEquals(trie.toMessage(), map.get(trie.getHash()));

        Assert.assertEquals(1, store.getSaveCount());
    }

    @Test
    public void saveFullTrie() {
        HashMapDB map = new HashMapDB();
        TrieStoreImpl store = new TrieStoreImpl(map);

        Trie trie = new TrieImpl(store, true).put("foo", "bar".getBytes());

        trie.save();

        Assert.assertEquals(trie.trieSize(), map.keys().size());
        Assert.assertNotNull(map.get(trie.getHash()));
        Assert.assertArrayEquals(trie.toMessage(), map.get(trie.getHash()));

        Assert.assertEquals(trie.trieSize(), store.getSaveCount());
    }

    @Test
    public void saveFullTrieTwice() {
        HashMapDB map = new HashMapDB();
        TrieStoreImpl store = new TrieStoreImpl(map);

        Trie trie = new TrieImpl(store, true).put("foo", "bar".getBytes());

        trie.save();

        Assert.assertEquals(trie.trieSize(), store.getSaveCount());

        trie.save();

        Assert.assertEquals(trie.trieSize(), store.getSaveCount());
    }

    @Test
    public void saveFullTrieUpdateAndSaveAgainUsingBinaryTrie() {
        HashMapDB map = new HashMapDB();
        TrieStoreImpl store = new TrieStoreImpl(map);

        Trie trie = new TrieImpl(store, true).put("foo", "bar".getBytes());

        trie.save();

        Assert.assertEquals(trie.trieSize(), store.getSaveCount());

        trie = trie.put("foo", "bar2".getBytes());

        trie.save();

        Assert.assertEquals(trie.trieSize() + 1, store.getSaveCount());
    }

    @Test
    public void saveFullTrieUpdateAndSaveAgainUsingArity16() {
        HashMapDB map = new HashMapDB();
        TrieStoreImpl store = new TrieStoreImpl(map);

        Trie trie = new TrieImpl(16, store, true).put("foo", "bar".getBytes());

        trie.save();

        Assert.assertEquals(trie.trieSize(), store.getSaveCount());

        trie = trie.put("foo", "bar2".getBytes());

        trie.save();

        Assert.assertEquals(trie.trieSize() + 1, store.getSaveCount());
    }

    @Test
    public void retrieveUnknownHash() {
        HashMapDB map = new HashMapDB();
        TrieStoreImpl store = new TrieStoreImpl(map);

        Assert.assertNull(store.retrieve(new byte[] { 0x01, 0x02, 0x03, 0x04 }));
    }

    @Test
    public void retrieveTrieByHash() {
        HashMapDB map = new HashMapDB();
        TrieStoreImpl store = new TrieStoreImpl(map);

        Trie trie = new TrieImpl(store, true).put("bar", "foo".getBytes())
                .put("foo", "bar".getBytes());


        trie.save();
        int size = trie.trieSize();

        Trie trie2 = store.retrieve(trie.getHash());

        Assert.assertEquals(1, store.getRetrieveCount());

        Assert.assertEquals(size, trie2.trieSize());

        Assert.assertEquals(size, store.getRetrieveCount());
    }
}
