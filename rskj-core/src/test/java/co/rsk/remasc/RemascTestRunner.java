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

package co.rsk.remasc;

import co.rsk.blockchain.utils.BlockchainBuilder;
import co.rsk.core.bc.BlockChainImpl;
import co.rsk.core.bc.BlockExecutor;
import co.rsk.crypto.Sha3Hash;
import co.rsk.peg.PegTestUtils;
import org.ethereum.core.*;
import org.ethereum.crypto.ECKey;
import org.ethereum.crypto.HashUtil;
import org.ethereum.util.RLP;
import org.spongycastle.util.BigIntegers;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by martin.medina on 1/5/17.
 */
class RemascTestRunner {
    private static final byte[] EMPTY_LIST_HASH = HashUtil.sha3(RLP.encodeList());

    private ECKey txSigningKey;

    private long txValue;

    private long minerFee;

    private int initialHeight;

    private List<SiblingElement> siblingElements;

    private List<Block> addedSiblings;

    private Blockchain blockchain;

    private BlockchainBuilder builder;

    private Block genesis;

    public RemascTestRunner(BlockchainBuilder blockchainBuilder, Block genesis) {
        this.builder = blockchainBuilder;
        this.genesis = genesis;
    }

    public RemascTestRunner txSigningKey(ECKey txSigningKey) {
        this.txSigningKey = txSigningKey;
        return this;
    }

    public RemascTestRunner txValue(long txValue) {
        this.txValue = txValue;
        return this;
    }

    public RemascTestRunner minerFee(long minerFee) {
        this.minerFee = minerFee;
        return this;
    }

    public RemascTestRunner initialHeight(int initialHeight) {
        this.initialHeight = initialHeight;
        return this;
    }

    public RemascTestRunner siblingElements(List<SiblingElement> siblingElements) {
        this.siblingElements = siblingElements;
        return this;
    }

    public List<Block> getAddedSiblings() {
        return addedSiblings;
    }

    public void start() {
        this.blockchain = this.builder.build();

        ((BlockChainImpl)this.blockchain).setNoValidation(true);

        this.addedSiblings = new ArrayList<>();
        List<Block> mainChainBlocks = new ArrayList<>();
        this.blockchain.tryToConnect(this.genesis);

        BlockExecutor blockExecutor = new BlockExecutor(blockchain.getRepository(), blockchain, blockchain.getBlockStore(), null);

        for(int i = 0; i <= this.initialHeight; i++) {
            int finalI = i;
            List<SiblingElement> siblingsForCurrentHeight = this.siblingElements.stream().filter(siblingElement -> siblingElement.getHeightToBeIncluded() == finalI).collect(Collectors.toList());
            List<BlockHeader> blockSiblings = new ArrayList<>();
            for(SiblingElement sibling : siblingsForCurrentHeight) {
                Sha3Hash siblingCoinbase = PegTestUtils.createHash3();
                Block mainchainSiblingParent = mainChainBlocks.get(sibling.getHeight() - 1);
                Block siblingBlock = createBlock(this.genesis, mainchainSiblingParent, PegTestUtils.createHash3(), siblingCoinbase, null, minerFee, Long.valueOf(i), this.txValue, this.txSigningKey);

                blockSiblings.add(siblingBlock.getHeader());
                this.addedSiblings.add(siblingBlock);
            }

            long txNonce = i;
            Sha3Hash coinbase = PegTestUtils.createHash3();
            Block block = createBlock(this.genesis, this.blockchain.getBestBlock(), PegTestUtils.createHash3(), coinbase, blockSiblings, minerFee, txNonce, this.txValue, this.txSigningKey);
            mainChainBlocks.add(block);

            blockExecutor.executeAndFillAll(block, this.blockchain.getBestBlock());

            ImportResult result = this.blockchain.tryToConnect(block);
            System.out.println(result);
        }
    }

    public Blockchain getBlockChain() {
        return this.blockchain;
    }

    public BigInteger getAccountBalance(byte[] address) {
        return getAccountBalance(this.blockchain.getRepository(), address);
    }

    public static BigInteger getAccountBalance(Repository repository, byte[] address) {
        AccountState accountState = repository.getAccountState(address);

        return accountState == null ? null : repository.getAccountState(address).getBalance();
    }

    public static Block createBlock(Block genesis, Block parentBlock, Sha3Hash blockHash, Sha3Hash coinbase, List<BlockHeader> uncles, long minerFee, long txNonce, long txValue, ECKey txSigningKey) {
        if (minerFee == 0) throw new IllegalArgumentException();
        Transaction tx = new Transaction(
                BigInteger.valueOf(txNonce).toByteArray(),
                BigInteger.ONE.toByteArray(),
                BigInteger.valueOf(minerFee).toByteArray(),
                new ECKey().getAddress() ,
                BigInteger.valueOf(txValue).toByteArray(),
                null,
                Transaction.getConfigChainId());

        tx.sign(txSigningKey.getPrivKeyBytes());

        return createBlock(genesis, parentBlock, blockHash, coinbase, uncles, tx);
    }

    public static Block createBlock(Block genesis, Block parentBlock, Sha3Hash blockHash, Sha3Hash coinbase, List<BlockHeader> uncles, Transaction... txsToInlcude) {
        List<Transaction> txs = new ArrayList<>();
        if (txsToInlcude != null) {
            for (Transaction tx : txsToInlcude) {
                txs.add(tx);
            }
        }

        Transaction remascTx = new RemascTransaction(parentBlock.getNumber() + 1);
        txs.add(remascTx);

        long difficulty = BigIntegers.fromUnsignedByteArray(parentBlock.getDifficulty()).longValue();

        if (difficulty == 0)
            difficulty = 1;

        byte[] diffBytes = BigInteger.valueOf(difficulty).toByteArray();

        Block block =  new Block(
                parentBlock.getHash(),          // parent hash
                EMPTY_LIST_HASH,       // uncle hash
                coinbase.getBytes(),            // coinbase
                new Bloom().getData(),          // logs bloom
                diffBytes,    // difficulty
                parentBlock.getNumber() + 1,
                parentBlock.getGasLimit(),
                parentBlock.getGasUsed(),
                parentBlock.getTimestamp(),
                new byte[0],                    // extraData
                new byte[0],                    // mixHash
                BigInteger.ZERO.toByteArray(),         // provisory nonce
                HashUtil.EMPTY_TRIE_HASH,       // receipts root
                BlockChainImpl.calcTxTrie(txs), // transaction root
                genesis.getStateRoot(),         //EMPTY_TRIE_HASH,   // state root
                txs,                            // transaction list
                uncles,                          // uncle list
                BigInteger.TEN.toByteArray()
        ) {
            private BlockHeader harcodedHashHeader;

            @Override
            public BlockHeader getHeader() {
                if (harcodedHashHeader==null) {
                    harcodedHashHeader = new BlockHeader(super.getHeader().getEncoded()) {
                        @Override
                        public byte[] getHash() {
                            return blockHash.getBytes();
                        }
                    };
                }
                return harcodedHashHeader;
            }

            @Override
            public byte[] getHash() {
                return blockHash.getBytes();
            }

            @Override
            public void flushRLP() {
                if (harcodedHashHeader != null)
                    super.getHeader().setPaidFees(harcodedHashHeader.getPaidFees());

                super.flushRLP();

                harcodedHashHeader = null;
            }
        };
        long paidFees = 0;
        for (Transaction tx : txs) {
            paidFees += BigIntegers.fromUnsignedByteArray(tx.getGasLimit()).longValue() * BigIntegers.fromUnsignedByteArray(tx.getGasPrice()).longValue();
        }
        block.getHeader().setPaidFees(paidFees);

        return block;
    }
}
