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

package co.rsk.config;

import org.spongycastle.util.encoders.Hex;

/**
 * Created by mario on 12/12/16.
 */
public class RemascConfig {

    // Number of blocks until mining fees are processed
    private long maturity;

    // Number of blocks block reward is split into
    private long syntheticSpan;

    // RSK labs address
    private String rskLabsAddress;

    // RSK labs cut. Available reward / rskLabsDivisor is what RSK gets.
    private long rskLabsDivisor = 5;

    // Punishment in case of broken selection rule. The punishment applied is available reward / punishmentDivisor.
    private long punishmentDivisor = 10;

    // Reward to block miners who included uncles in their blocks. Available reward / publishersDivisor is the total reward.
    private long publishersDivisor = 10;

    private long lateUncleInclusionPunishmentDivisor = 5;

    public long getMaturity() {
        return maturity;
    }

    public long getSyntheticSpan() {
        return syntheticSpan;
    }

    public byte[] getRskLabsAddress() {
        return Hex.decode(this.rskLabsAddress);
    }

    public long getRskLabsDivisor() {
        return rskLabsDivisor;
    }

    public long getPunishmentDivisor() {
        return punishmentDivisor;
    }

    public long getLateUncleInclusionPunishmentDivisor() { return this.lateUncleInclusionPunishmentDivisor; }

    public long getPublishersDivisor() {
        return publishersDivisor;
    }
}
