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

package co.rsk.rpc;

import co.rsk.config.RskSystemProperties;
import co.rsk.mine.MinerServer;
import co.rsk.config.RskMiningConstants;
import co.rsk.core.Rsk;
import co.rsk.core.Wallet;
import co.rsk.core.WalletFactory;
import co.rsk.mine.MinerWork;
import org.apache.commons.lang3.ArrayUtils;
import org.ethereum.core.Block;
import org.ethereum.crypto.SHA3Helper;
import org.ethereum.rpc.TypeConverter;
import org.ethereum.rpc.Web3Impl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.util.Collections;
import java.util.List;

/**
 * Created by adrian.eidelman on 3/11/2016.
 */
public class Web3RskImpl extends Web3Impl {
    private static final Logger logger = LoggerFactory.getLogger("web3");

    MinerServer minerServer;

    public Web3RskImpl(Rsk rsk) {
        super(rsk, RskSystemProperties.RSKCONFIG, WalletFactory.createPersistentWallet());
        this.minerServer = rsk.getMinerServer();
    }

    public Web3RskImpl(Rsk rsk, Wallet wallet) {
        super(rsk, RskSystemProperties.RSKCONFIG, wallet);
        this.minerServer = rsk.getMinerServer();
    }

    public MinerWork mnr_getWork() {
        if (logger.isDebugEnabled()) logger.debug("mnr_getWork()");
        return minerServer.getWork();
    }

    public void mnr_submitBitcoinBlock(String bitcoinBlockHex) {
        if (logger.isDebugEnabled()) logger.debug("mnr_submitBitcoinBlock(): " + bitcoinBlockHex.length());
        co.rsk.bitcoinj.core.NetworkParameters params = co.rsk.bitcoinj.params.RegTestParams.get();
        new co.rsk.bitcoinj.core.Context(params);
        byte[] bitcoinBlockByteArray = Hex.decode(bitcoinBlockHex);
        co.rsk.bitcoinj.core.BtcBlock bitcoinBlock = params.getDefaultSerializer().makeBlock(bitcoinBlockByteArray);
        co.rsk.bitcoinj.core.BtcTransaction coinbase = bitcoinBlock.getTransactions().get(0);
        byte[] coinbaseAsByteArray = coinbase.bitcoinSerialize();
        List<Byte> coinbaseAsByteList = java.util.Arrays.asList(ArrayUtils.toObject(coinbaseAsByteArray));

        List<Byte> rskTagAsByteList = java.util.Arrays.asList(ArrayUtils.toObject(RskMiningConstants.RSK_TAG));

        int rskTagPosition = Collections.lastIndexOfSubList(coinbaseAsByteList, rskTagAsByteList);
        byte[] blockHashForMergedMiningArray = new byte[SHA3Helper.Size.S256.getValue()/8];
        System.arraycopy(coinbaseAsByteArray, rskTagPosition+ RskMiningConstants.RSK_TAG.length, blockHashForMergedMiningArray, 0, blockHashForMergedMiningArray.length);
        String blockHashForMergedMining = TypeConverter.toJsonHex(blockHashForMergedMiningArray);

        minerServer.submitBitcoinBlock(blockHashForMergedMining, bitcoinBlock);
    }

    public void ext_dumpState()  {
        Block bestBlcock = worldManager.getBlockStore().getBestBlock();
        logger.info("Dumping state for block hash {}, block number {}", Hex.toHexString(bestBlcock.getHash()), bestBlcock.getNumber());
        this.worldManager.getNetworkStateExporter().exportStatus(System.getProperty("user.dir") + "/" + "rskdump.json");
    }

}
