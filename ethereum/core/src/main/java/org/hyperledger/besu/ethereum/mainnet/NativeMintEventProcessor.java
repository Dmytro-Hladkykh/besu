/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.mainnet;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes MintRequested events from the Native Mint contract
 * and executes native token mints by modifying account balances.
 *
 * <p>Event signature: MintRequested(address indexed recipient, uint256 amount)
 */
public class NativeMintEventProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(NativeMintEventProcessor.class);

  // Event signature hash for MintRequested event
  // keccak256("MintRequested(address,uint256)")
  private static final Bytes32 MINT_REQUESTED_EVENT_SIGNATURE =
      Hash.hash(Bytes.wrap("MintRequested(address,uint256)".getBytes(StandardCharsets.UTF_8)));

  private static final int MINT_EVENT_TOPICS_COUNT = 2; // signature + indexed recipient
  private static final int MINT_EVENT_DATA_SIZE = 32;   // uint256 amount

  private final Address mintContractAddress;

  /**
   * Creates a new NativeMintEventProcessor.
   *
   * @param mintContractAddress the address of the mint contract (required)
   */
  public NativeMintEventProcessor(final Address mintContractAddress) {
    this.mintContractAddress = mintContractAddress;
    LOG.info("NativeMintEventProcessor initialized with contarct address: {}", mintContractAddress);
  }

  /**
   * Process transaction logs to detect and execute mint requests.
   *
   * <p>This method should be called after transaction execution, before the final world state
   * commit.
   *
   * @param logs the transaction logs
   * @param worldUpdater the world updater
   */
  public void processLogs(final List<Log> logs, final WorldUpdater worldUpdater) {

    if (logs == null || logs.isEmpty()) {
      return;
    }

    for (final Log log : logs) {
      // Check if the log is from the configured contract
      if (!log.getLogger().equals(mintContractAddress)) {
        continue; // Not from configured contract
      }

      // Check if it's a MintRequested event
      if (log.getTopics().isEmpty()
          || !log.getTopics().get(0).equals(MINT_REQUESTED_EVENT_SIGNATURE)) {
        continue; // Not a MintRequested event
      }

      try {
        processMintRequest(log, worldUpdater);
      } catch (final Exception e) {
        LOG.error("Error processing mint request from log: {}", log, e);
        // Continue processing other logs even if one fails
      }
    }
  }

  /**
   * Process a single MintRequested event and execute the mint.
   *
   * @param log the event log
   * @param worldUpdater the world updater
   */
  private void processMintRequest(final Log log, final WorldUpdater worldUpdater) {

    // Decode event parameters
    // Topics: [0] = signature, [1] = recipient (indexed)
    // Data: amount (32 bytes)

    if (log.getTopics().size() != MINT_EVENT_TOPICS_COUNT) {
      LOG.warn(
          "Invalid MintRequested event: expected {} topics, got {}. Log: {}",
          MINT_EVENT_TOPICS_COUNT,
          log.getTopics().size(),
          log);
      return;
    }

    try {
      // Extract indexed parameters from topics
      final Address recipient = Address.wrap(log.getTopics().get(1).slice(12, 20));

      // Extract non-indexed parameters from data
      final Bytes data = log.getData();
      if (data.size() != MINT_EVENT_DATA_SIZE) {
        LOG.warn(
            "Invalid MintRequested event data: expected {} bytes, got {}. Log: {}",
            MINT_EVENT_DATA_SIZE,
            data.size(),
            log);
        return;
      }

      final Wei amount = Wei.wrap(data.slice(0, 32));

      // Validate parameters
      if (amount.isZero()) {
        LOG.warn("Invalid mint amount: {}. Recipient: {}", amount, recipient);
        return;
      }

      if (recipient.equals(Address.ZERO)) {
        LOG.warn("Invalid recipient address: zero address");
        return;
      }

      LOG.trace(
          "Processing mint request: recipient={}, amount={}, mintContract={}",
          recipient,
          amount,
          log.getLogger());

      // Execute the mint
      executeMint(recipient, amount, worldUpdater);

      LOG.trace(
          "Mint executed successfully: recipient={}, amount={}",
          recipient,
          amount);

    } catch (final Exception e) {
      LOG.error("Error decoding or executing mint request from log: {}", log, e);
    }
  }

  /**
   * Execute the actual mint operation by modifying the world state.
   *
   * @param recipient the recipient address
   * @param amount the amount to mint
   * @param worldUpdater the world updater
   */
  private void executeMint(
      final Address recipient, final Wei amount, final WorldUpdater worldUpdater) {

    // Get or create the recipient account
    final MutableAccount account = worldUpdater.getOrCreate(recipient);

    // Get current balance for logging
    final Wei currentBalance = account.getBalance();

    // Increment balance (uses addExact internally, will throw ArithmeticException on overflow)
    account.incrementBalance(amount);

    LOG.trace(
        "Balance updated for {}: {} + {} = {}",
        recipient,
        currentBalance,
        amount,
        account.getBalance());
  }

}
