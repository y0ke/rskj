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

package org.ethereum.config.blockchain;

import co.rsk.config.BridgeConstants;
import co.rsk.config.BridgeTestNetConstants;
import org.ethereum.config.Constants;
import org.ethereum.core.BlockHeader;

import java.math.BigInteger;


public class TestNetAfterBridgeSyncConfig extends GenesisConfig {


    public static class TestNetConstants extends GenesisConstants {
        private static final BigInteger DIFFICULTY_BOUND_DIVISOR = BigInteger.valueOf(50);
        private static final byte CHAIN_ID = 31;

        @Override
        public BridgeConstants getBridgeConstants() {
            return BridgeTestNetConstants.getInstance();
        }

        @Override
        public int getDURATION_LIMIT() {
            return 14;
        }

        @Override
        public BigInteger getDIFFICULTY_BOUND_DIVISOR() {
            return DIFFICULTY_BOUND_DIVISOR;
        }

        @Override
        public int getNewBlockMaxMinInTheFuture() {
            return 540;
        }

        @Override
        public byte getChainId() {
            return TestNetConstants.CHAIN_ID;
        }

    }

    public TestNetAfterBridgeSyncConfig() {
        super(new TestNetConstants());
    }

    protected TestNetAfterBridgeSyncConfig(Constants constants) {
        super(constants);
    }


    @Override
    public BigInteger calcDifficulty(BlockHeader curBlock, BlockHeader parent) {
        // If more than 10 minutes, reset to original difficulty 0x00100000
        if (curBlock.getTimestamp() >= parent.getTimestamp() + 600)
            return getConstants().getMINIMUM_DIFFICULTY();

        return super.calcDifficulty(curBlock, parent);
    }
}
