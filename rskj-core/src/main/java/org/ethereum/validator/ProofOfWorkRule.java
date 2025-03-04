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

package org.ethereum.validator;

import co.rsk.config.RskMiningConstants;
import co.rsk.util.DifficultyUtils;
import co.rsk.validators.BlockValidationRule;
import org.apache.commons.lang3.ArrayUtils;
import org.ethereum.config.SystemProperties;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.digests.SHA256Digest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Checks proof value against its boundary for the block header
 *
 * @author Mikhail Kalinin
 * @since 02.09.2015
 */
public class ProofOfWorkRule implements BlockValidationRule {

    private static final Logger logger = LoggerFactory.getLogger("blockvalidator");

    @Override
    public boolean isValid(Block block) {

        BlockHeader header = block.getHeader();
        co.rsk.bitcoinj.core.NetworkParameters bitcoinNetworkParameters = SystemProperties.CONFIG.getBlockchainConfig().getCommonConstants().getBridgeConstants().getBtcParams();
        byte[] bitcoinMergedMiningCoinbaseTransactionCompressed = header.getBitcoinMergedMiningCoinbaseTransaction();
        co.rsk.bitcoinj.core.BtcBlock bitcoinMergedMiningBlock = bitcoinNetworkParameters.getDefaultSerializer().makeBlock(header.getBitcoinMergedMiningHeader());
        co.rsk.bitcoinj.core.PartialMerkleTree bitcoinMergedMiningMerkleBranch  = new co.rsk.bitcoinj.core.PartialMerkleTree(bitcoinNetworkParameters, header.getBitcoinMergedMiningMerkleProof(), 0);

        BigInteger target = DifficultyUtils.difficultyToTarget(header.getDifficultyBI());

        BigInteger bitcoinMergedMiningBlockHashBI = bitcoinMergedMiningBlock.getHash().toBigInteger();

        if (bitcoinMergedMiningBlockHashBI.compareTo(target) > 0) {
            logger.error("Hash {} is higher than target {}", bitcoinMergedMiningBlockHashBI.toString(16), target.toString(16));
            return false;
        }
        
        byte[] bitcoinMergedMiningCoinbaseTransactionMidstate = new byte[RskMiningConstants.MIDSTATE_SIZE];
        System.arraycopy(bitcoinMergedMiningCoinbaseTransactionCompressed, 0, bitcoinMergedMiningCoinbaseTransactionMidstate, 8, RskMiningConstants.MIDSTATE_SIZE_TRIMMED);

        byte[] bitcoinMergedMiningCoinbaseTransactionTail = new byte[bitcoinMergedMiningCoinbaseTransactionCompressed.length - RskMiningConstants.MIDSTATE_SIZE_TRIMMED];
        System.arraycopy(bitcoinMergedMiningCoinbaseTransactionCompressed, RskMiningConstants.MIDSTATE_SIZE_TRIMMED,
                bitcoinMergedMiningCoinbaseTransactionTail, 0, bitcoinMergedMiningCoinbaseTransactionTail.length);

        byte[] expectedCoinbaseMessageBytes = org.spongycastle.util.Arrays.concatenate(RskMiningConstants.RSK_TAG, header.getHashForMergedMining());


        List<Byte> bitcoinMergedMiningCoinbaseTransactionTailAsList = java.util.Arrays.asList(ArrayUtils.toObject(bitcoinMergedMiningCoinbaseTransactionTail));
        List<Byte> expectedCoinbaseMessageBytesAsList = java.util.Arrays.asList(ArrayUtils.toObject(expectedCoinbaseMessageBytes));

        int rskTagPosition = Collections.lastIndexOfSubList(bitcoinMergedMiningCoinbaseTransactionTailAsList, expectedCoinbaseMessageBytesAsList);
        if (rskTagPosition == -1) {
            logger.error("bitcoin coinbase transaction tail message does not contain expected RSKBLOCK:RskBlockHeaderHash. Expected: {} . Actual: {} .", Arrays.toString(expectedCoinbaseMessageBytes), Arrays.toString(bitcoinMergedMiningCoinbaseTransactionTail));
            return false;
        }

        /*
        * We check that the there is no other block before the rsk tag, to avoid a possible malleability attack:
        * If we have a mid state with 10 blocks, and the rsk tag, we can also have
        * another mid state with 9 blocks, 64bytes + the rsk tag, giving us two blocks with different hashes but the same spv proof.
        * */
        if (rskTagPosition >= 64) {
            logger.error("bitcoin coinbase transaction tag position is bigger than expected 64. Actual: {}.", Integer.toString(rskTagPosition));
            return false;
        }

        List<Byte> rskTagAsList = java.util.Arrays.asList(ArrayUtils.toObject(RskMiningConstants.RSK_TAG));
        if (rskTagPosition !=
                Collections.lastIndexOfSubList(bitcoinMergedMiningCoinbaseTransactionTailAsList, rskTagAsList)) {
            logger.error("The valid RSK tag is not the last RSK tag. Tail: {}.", Arrays.toString(bitcoinMergedMiningCoinbaseTransactionTail));
            return false;
        }

        int remainingByteCount = bitcoinMergedMiningCoinbaseTransactionTail.length -
                rskTagPosition -
                RskMiningConstants.RSK_TAG.length -
                RskMiningConstants.BLOCK_HEADER_HASH_SIZE;
        if (remainingByteCount > RskMiningConstants.MAX_BYTES_AFTER_MERGED_MINING_HASH) {
            logger.error("More than 128 bytes after ROOOTSTOCK tag");
            return false;
        }

        SHA256Digest digest = new SHA256Digest(bitcoinMergedMiningCoinbaseTransactionMidstate);
        digest.update(bitcoinMergedMiningCoinbaseTransactionTail,0,bitcoinMergedMiningCoinbaseTransactionTail.length);
        byte[] bitcoinMergedMiningCoinbaseTransactionOneRoundOfHash = new byte[32];
        digest.doFinal(bitcoinMergedMiningCoinbaseTransactionOneRoundOfHash, 0);
        co.rsk.bitcoinj.core.Sha256Hash bitcoinMergedMiningCoinbaseTransactionHash = co.rsk.bitcoinj.core.Sha256Hash.wrapReversed(co.rsk.bitcoinj.core.Sha256Hash.hash(bitcoinMergedMiningCoinbaseTransactionOneRoundOfHash));

        List<co.rsk.bitcoinj.core.Sha256Hash> txHashesInTheMerkleBranch = new ArrayList<>();
        co.rsk.bitcoinj.core.Sha256Hash merkleRoot = bitcoinMergedMiningMerkleBranch.getTxnHashAndMerkleRoot(txHashesInTheMerkleBranch);
        if (!merkleRoot.equals(bitcoinMergedMiningBlock.getMerkleRoot())) {
            logger.error("bitcoin merkle root of bitcoin block does not match the merkle root of merkle branch");
            return false;
        }
        if (!txHashesInTheMerkleBranch.contains(bitcoinMergedMiningCoinbaseTransactionHash)) {
            logger.error("bitcoin coinbase transaction {} not included in merkle branch", bitcoinMergedMiningCoinbaseTransactionHash);
            return false;
        }

        return true;
    }
}
