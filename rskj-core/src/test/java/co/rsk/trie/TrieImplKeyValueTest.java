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

import org.junit.Assert;
import org.junit.Test;

/**
 * Created by ajlopez on 11/01/2017.
 */
public class TrieImplKeyValueTest {
    @Test
    public void getNullForUnknownKey() {
        TrieImpl trie = new TrieImpl();

        Assert.assertNull(trie.get(new byte[] { 0x01, 0x02, 0x03 }));
        Assert.assertNull(trie.get("foo"));
    }

    @Test
    public void putAndGetKeyValue() {
        Trie trie = new TrieImpl();

        trie = trie.put("foo", "bar".getBytes());
        Assert.assertNotNull(trie.get("foo"));
        Assert.assertArrayEquals("bar".getBytes(), trie.get("foo"));
    }

    @Test
    public void putKeyValueAndDeleteKey() {
        Trie trie = new TrieImpl();

        trie = trie.put("foo", "bar".getBytes()).delete("foo");
        Assert.assertNull(trie.get("foo"));
    }

    @Test
    public void putAndGetEmptyKeyValue() {
        Trie trie = new TrieImpl();

        trie = trie.put("", "bar".getBytes());
        Assert.assertNotNull(trie.get(""));
        Assert.assertArrayEquals("bar".getBytes(), trie.get(""));
    }

    @Test
    public void putAndGetTwoKeyValues() {
        Trie trie = new TrieImpl();

        trie = trie.put("foo", "bar".getBytes());
        trie = trie.put("bar", "foo".getBytes());

        Assert.assertNotNull(trie.get("foo"));
        Assert.assertArrayEquals("bar".getBytes(), trie.get("foo"));

        Assert.assertNotNull(trie.get("bar"));
        Assert.assertArrayEquals("foo".getBytes(), trie.get("bar"));
    }

    @Test
    public void putAndGetKeyAndSubKeyValues() {
        Trie trie = new TrieImpl();

        trie = trie.put("foo", "bar".getBytes());
        trie = trie.put("f", "42".getBytes());

        Assert.assertNotNull(trie.get("foo"));
        Assert.assertArrayEquals("bar".getBytes(), trie.get("foo"));

        Assert.assertNotNull(trie.get("f"));
        Assert.assertArrayEquals("42".getBytes(), trie.get("f"));
    }

    @Test
    public void putAndGetKeyAndSubKeyValuesInverse() {
        Trie trie = new TrieImpl();

        trie = trie.put("f", "42".getBytes())
                .put("fo", "bar".getBytes());

        Assert.assertNotNull(trie.get("fo"));
        Assert.assertArrayEquals("bar".getBytes(), trie.get("fo"));

        Assert.assertNotNull(trie.get("f"));
        Assert.assertArrayEquals("42".getBytes(), trie.get("f"));
    }

    @Test
    public void putAndGetOneHundredKeyValues() {
        Trie trie = new TrieImpl(16, false);

        for (int k = 0; k < 100; k++)
            trie = trie.put(k + "", (k + "").getBytes());

        for (int k = 0; k < 100; k++) {
            String key = k + "";
            byte[] expected = key.getBytes();
            byte[] value = trie.get(key);
            Assert.assertArrayEquals(key, value, expected);
        }
    }

    @Test
    public void putAndGetOneHundredKeyValuesUsingBinaryTree() {
        Trie trie = new TrieImpl();

        for (int k = 0; k < 100; k++)
            trie = trie.put(k + "", (k + "").getBytes());

        for (int k = 0; k < 100; k++) {
            String key = k + "";
            byte[] expected = key.getBytes();
            byte[] value = trie.get(key);
            Assert.assertArrayEquals(key, value, expected);
        }
    }
}
