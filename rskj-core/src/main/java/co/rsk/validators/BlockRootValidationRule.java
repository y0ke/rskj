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

package co.rsk.validators;

import co.rsk.panic.PanicProcessor;
import org.ethereum.core.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

/**
 * Validate the transaction root of a block.
 * It calculates the transaction root hash given the block transaction list
 * and compares the result with the transaction root hash in block header
 *
 * @return true if the transaction root is valid, false if the transaction root is invalid
 */
public class BlockRootValidationRule implements BlockValidationRule {

    private static final Logger logger = LoggerFactory.getLogger("blockvalidator");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    @Override
    public boolean isValid(Block block) {
        String trieHash = Hex.toHexString(block.getTxTrieRoot());
        String trieListHash = Hex.toHexString(Block.getTxTrie(block.getTransactionsList()).getHash());

        boolean isValid = true;

        if (!trieHash.equals(trieListHash)) {
            logger.error("Block's given Trie Hash doesn't match: {} != {}", trieHash, trieListHash);
            panicProcessor.panic("invalidtrie", String.format("Block's given Trie Hash doesn't match: %s != %s", trieHash, trieListHash));
            isValid = false;
        }
        return isValid;
    }
}
