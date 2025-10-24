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
 * Processes MintRequested and BurnRequested events from the Native Mint contract
 * and executes native token mints and burns by modifying account balances.
 *
 * <p>Event signatures:
 * <ul>
 *   <li>MintRequested(address indexed recipient, uint256 amount)
 *   <li>BurnRequested(address indexed from, uint256 amount)
 * </ul>
 */
public class NativeMintEventProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(NativeMintEventProcessor.class);

  // Event signature hash for MintRequested event
  // keccak256("MintRequested(address,uint256)")
  private static final Bytes32 MINT_REQUESTED_EVENT_SIGNATURE =
      Hash.hash(Bytes.wrap("MintRequested(address,uint256)".getBytes(StandardCharsets.UTF_8)));

  // Event signature hash for BurnRequested event
  // keccak256("BurnRequested(address,uint256)")
  private static final Bytes32 BURN_REQUESTED_EVENT_SIGNATURE =
      Hash.hash(Bytes.wrap("BurnRequested(address,uint256)".getBytes(StandardCharsets.UTF_8)));

  private static final int MINT_EVENT_TOPICS_COUNT = 2; // signature + indexed recipient
  private static final int MINT_EVENT_DATA_SIZE = 32;   // uint256 amount
  private static final int BURN_EVENT_TOPICS_COUNT = 2; // signature + indexed from
  private static final int BURN_EVENT_DATA_SIZE = 32;   // uint256 amount

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
   * Process transaction logs to detect and execute mint and burn requests.
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

      if (log.getTopics().isEmpty()) {
        continue; // No event signature
      }

      final Bytes32 eventSignature = log.getTopics().get(0);

      try {
        // Check if it's a MintRequested event
        if (eventSignature.equals(MINT_REQUESTED_EVENT_SIGNATURE)) {
          processMintRequest(log, worldUpdater);
        }
        // Check if it's a BurnRequested event
        else if (eventSignature.equals(BURN_REQUESTED_EVENT_SIGNATURE)) {
          processBurnRequest(log, worldUpdater);
        }
      } catch (final Exception e) {
        LOG.error("Error processing native token event from log: {}", log, e);
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
    // WARNING: Creating new accounts consumes computational resources (~25k gas)
    // The cost is NOT charged to transaction gas as minting occurs after execution completes
    // Ensure the mint contract implements proper limiting and access controls
    // to prevent resource exhaustion attacks via excessive account creation
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

  /**
   * Process a BurnRequested event and execute the burn.
   *
   * @param log the event log
   * @param worldUpdater the world updater
   */
  private void processBurnRequest(final Log log, final WorldUpdater worldUpdater) {

    // Decode event parameters
    // Topics: [0] = signature, [1] = from (indexed)
    // Data: amount (32 bytes)

    if (log.getTopics().size() != BURN_EVENT_TOPICS_COUNT) {
      LOG.warn(
          "Invalid BurnRequested event: expected {} topics, got {}. Log: {}",
          BURN_EVENT_TOPICS_COUNT,
          log.getTopics().size(),
          log);
      return;
    }

    try {
      // Extract indexed parameters from topics
      final Address from = Address.wrap(log.getTopics().get(1).slice(12, 20));

      // Extract non-indexed parameters from data
      final Bytes data = log.getData();
      if (data.size() != BURN_EVENT_DATA_SIZE) {
        LOG.warn(
            "Invalid BurnRequested event data: expected {} bytes, got {}. Log: {}",
            BURN_EVENT_DATA_SIZE,
            data.size(),
            log);
        return;
      }

      final Wei amount = Wei.wrap(data.slice(0, 32));

      // Validate parameters
      if (amount.isZero()) {
        LOG.warn("Invalid burn amount: {}. From: {}", amount, from);
        return;
      }

      if (from.equals(Address.ZERO)) {
        LOG.warn("Invalid from address: zero address");
        return;
      }

      LOG.trace(
          "Processing burn request: from={}, amount={}, contract={}",
          from,
          amount,
          log.getLogger());

      // Execute the burn
      executeBurn(from, amount, worldUpdater);

      LOG.trace(
          "Burn executed successfully: from={}, amount={}",
          from,
          amount);

    } catch (final Exception e) {
      LOG.error("Error decoding or executing burn request from log: {}", log, e);
    }
  }

  /**
   * Execute the actual burn operation by modifying the world state.
   *
   * @param from the account to burn from
   * @param amount the amount to burn
   * @param worldUpdater the world updater
   */
  private void executeBurn(
      final Address from, final Wei amount, final WorldUpdater worldUpdater) {

    // Get the account (if it doesn't exist, there's nothing to burn)
    final MutableAccount account = worldUpdater.getAccount(from);

    if (account == null) {
      LOG.warn("Cannot burn from non-existent account: {}", from);
      return;
    }

    // Get current balance for logging
    final Wei currentBalance = account.getBalance();

    try {
      // Decrement balance (uses subtract internally, will throw IllegalStateException on insufficient balance)
      account.decrementBalance(amount);

      LOG.trace(
          "Balance updated for {}: {} - {} = {}",
          from,
          currentBalance,
          amount,
          account.getBalance());
    } catch (final IllegalStateException e) {
      LOG.warn(
          "Insufficient balance for burn: account={}, balance={}, requested={}. Error: {}",
          from,
          currentBalance,
          amount,
          e.getMessage());
    }
  }

}
