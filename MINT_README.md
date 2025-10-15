# Native Token Minting Support

This document describes the modifications made to Hyperledger Besu to support native token minting through smart contract events.

## Overview

This fork adds the ability for designated smart contracts to mint native tokens (ETH/native currency) by emitting special events. When a configured contract emits a `MintRequested` event, the Besu node automatically increases the recipient's native token balance.

## How It Works

1. **Genesis Configuration**: The mint contract address is specified in the genesis configuration file
2. **Event Detection**: During transaction processing, the `NativeMintEventProcessor` monitors logs for `MintRequested` events from the configured contract
3. **Balance Modification**: When a valid mint event is detected, the recipient's balance is increased by the specified amount
4. **State Commitment**: The balance change is committed to the world state along with other transaction effects

### Processing Flow

```
Transaction Execution
    ↓
Event Logs Generated
    ↓
NativeMintEventProcessor.processLogs()
    ↓
Filter logs by mint contract address
    ↓
Decode MintRequested events
    ↓
Validate recipient and amount
    ↓
Increment recipient balance
    ↓
World State Commit
```

## Minting Contract Requirements

The minting contract must emit events with the following specification:

### Event Signature

```solidity
event MintRequested(address indexed recipient, uint256 amount);
```

### Event Structure

- **Event Name**: `MintRequested`
- **Event Signature Hash**: `keccak256("MintRequested(address,uint256)")`
- **Topics**:
  - `topics[0]`: Event signature hash
  - `topics[1]`: Recipient address (indexed, 32 bytes with 12-byte padding)
- **Data**: Amount to mint (32 bytes, uint256)

### Validation Rules

The processor validates:
1. Event is from the configured mint contract address
2. Event signature matches `MintRequested(address,uint256)`
3. Amount is greater than zero
4. Recipient is not the zero address (0x0000...0000)

### Example Solidity Contract

```solidity
// SPDX-License-Identifier: MIT
pragma solidity 0.8.17;

/**
 * @title MintEventEmitter
 * @notice Simple contract that emits MintRequested events
 */
contract MintEventEmitter {
    /**
     * @notice Event emitted when mint is requested
     * @param recipient The address that will receive tokens
     * @param amount The amount of tokens to mint
     */
    event MintRequested(
        address indexed recipient,
        uint256 amount
    );

    /**
     * @notice Request a mint operation
     * @param recipient_ The address that will receive tokens
     * @param amount_ The amount of tokens to mint
     */
    function mint(address recipient_, uint256 amount_) external {
        emit MintRequested(recipient_, amount_);
    }
}
```

## Genesis Configuration

To enable native minting, add the `nativemintaddress` field to genesis configuration:

```json
{
  "config": {
    "chainId": 1337,
    "homesteadBlock": 0,
    "eip150Block": 0,
    "eip155Block": 0,
    "eip158Block": 0,
    "byzantiumBlock": 0,
    "constantinopleBlock": 0,
    "petersburgBlock": 0,
    "istanbulBlock": 0,
    "berlinBlock": 0,
    "londonBlock": 0,
    "nativemintaddress": "0x1234567890123456789012345678901234567890"
  },
  "nonce": "0x0",
  "timestamp": "0x0",
  "extraData": "0x",
  "gasLimit": "0x1C9C380",
  "difficulty": "0x1",
  "mixHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
  "coinbase": "0x0000000000000000000000000000000000000000",
  "alloc": {}
}
```

### Configuration Options

- **`nativemintaddress`** (optional): The address of the contract authorized to emit mint events
  - If not specified, native minting is disabled
  - Only events from this exact address will be processed
