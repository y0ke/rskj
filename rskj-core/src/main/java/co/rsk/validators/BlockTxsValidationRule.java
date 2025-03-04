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
import org.apache.commons.collections4.CollectionUtils;
import org.ethereum.core.Block;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.ethereum.db.ByteArrayWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.math.BigInteger.ONE;

/**
 * Validate a transactions list.
 * It validates each transaction nonce againts the expeced sender account nonce
 * It allows many transactions with the same sender
 *
 * @return true if the transactions are valid, false if any transaction is invalid
 */
public class BlockTxsValidationRule implements BlockParentDependantValidationRule{

    private static final Logger logger = LoggerFactory.getLogger("blockvalidator");
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private Repository repository;

    public BlockTxsValidationRule(Repository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isValid(Block block, Block parent) {
        if(block == null || parent == null) {
            return false;
        }

        List<Transaction> txs = block.getTransactionsList();
        if (CollectionUtils.isEmpty(txs))
            return true;

        Repository parentRepo = repository.getSnapshotTo(parent.getStateRoot());

        Map<ByteArrayWrapper, BigInteger> curNonce = new HashMap<>();

        for (Transaction tx : txs) {
            byte[] txSender = tx.getSender();
            ByteArrayWrapper key = new ByteArrayWrapper(txSender);
            BigInteger expectedNonce = curNonce.get(key);
            if (expectedNonce == null) {
                expectedNonce = parentRepo.getNonce(txSender);
            }
            curNonce.put(key, expectedNonce.add(ONE));
            BigInteger txNonce = new BigInteger(1, tx.getNonce());

            if (!expectedNonce.equals(txNonce)) {
                logger.error("Invalid transaction: Tx nonce {} != expected nonce {} (parent nonce: {}): {}",
                        txNonce, expectedNonce, parentRepo.getNonce(txSender), tx);

                panicProcessor.panic("invalidtransaction", String.format("Invalid transaction: Tx nonce %s != expected nonce %s (parent nonce: %s): %s",
                        txNonce.toString(), expectedNonce.toString(), parentRepo.getNonce(txSender).toString(), Hex.toHexString(tx.getHash())));

                return false;
            }
        }

        return true;
    }
}
