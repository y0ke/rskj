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

import org.ethereum.config.BlockchainConfig;
import org.ethereum.config.BlockchainNetConfig;
import org.ethereum.config.Constants;
import org.ethereum.core.BlockHeader;

import java.math.BigInteger;

import static org.ethereum.util.BIUtil.max;

/**
 * BlockchainForkConfig is also implemented by this class - its (mostly testing) purpose to represent
 * the specific config for all blocks on the chain (kinda constant config).
 *
 * Created by Anton Nashatyrev on 25.02.2016.
 */
public abstract class AbstractConfig implements BlockchainConfig, BlockchainNetConfig {
    protected Constants constants;

    public AbstractConfig() {
        this(new Constants());
    }

    public AbstractConfig(Constants constants) {
        this.constants = constants;
    }

    @Override
    public Constants getConstants() {
        return constants;
    }

    @Override
    public BlockchainConfig getConfigForBlock(long blockHeader) {
        return this;
    }

    @Override
    public Constants getCommonConstants() {
        return getConstants();
    }

    @Override
    public BigInteger calcDifficulty(BlockHeader curBlockHeader, BlockHeader parent) {
        BigInteger pd = parent.getDifficultyBI();

        int uncleCount = curBlockHeader.getUncleCount();
        long delta = curBlockHeader.getTimestamp()-parent.getTimestamp();
        if (delta<0)
            return pd;

        int calcDur =(1+uncleCount)*getConstants().getDURATION_LIMIT();
        int sign = 0;
        if (calcDur>delta)
            sign =1;
        if (calcDur<delta)
            sign =-1;

        if (sign==0)
            return pd;

        BigInteger quotient = pd.divide(getConstants().getDIFFICULTY_BOUND_DIVISOR());
        BigInteger difficulty;

        BigInteger fromParent;
        if (sign==1)
            fromParent =pd.add(quotient);
        else
            fromParent =pd.subtract(quotient);

        // If parent difficulty is zero (maybe a genesis block), then the first child difficulty MUST
        // be greater or equal getMINIMUM_DIFFICULTY(). That's why the max() is applied in both the add and the sub
        // cases
        difficulty = max(getConstants().getMINIMUM_DIFFICULTY(), fromParent);

        return difficulty;
    }

    protected abstract BigInteger getCalcDifficultyMultiplier(BlockHeader curBlock, BlockHeader parent);

    @Override
    public boolean areBridgeTxsFree() {
        return false;
    }
}
