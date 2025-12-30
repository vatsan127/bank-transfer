# Spring Transaction Notes

## Project Setup

```bash
# Create the database
psql -d postgres

create database bank_db;

\q
```

---

## Entity Relationship

```
┌─────────────────┐
│     Account     │
├─────────────────┤
│ id (PK)         │
│ accountNumber   │
│ accountHolder   │
│ balance         │
│ createdAt       │
│ updatedAt       │
└─────────────────┘
```

---

## Overview

A **transaction** is a sequence of operations performed as a single logical unit of work. Either all operations succeed (commit), or all fail (rollback). This is crucial for maintaining data integrity in operations like bank transfers.

---

## Key Concepts

### ACID Properties

- **Atomicity**: All or nothing - either all operations complete or none do
- **Consistency**: Database moves from one valid state to another valid state
- **Isolation**: Concurrent transactions don't interfere with each other
- **Durability**: Committed changes persist even after system crash

### Declarative vs Programmatic

- **Declarative**: Using `@Transactional` annotation (recommended)
- **Programmatic**: Using `TransactionTemplate` or `PlatformTransactionManager` directly

### Where to Place @Transactional

- **Service Layer**: ✅ Recommended - business logic belongs here
- **Repository Layer**: ❌ Too granular - each query becomes separate transaction
- **Controller Layer**: ❌ Controller should delegate to service
- **Class Level**: ⚠️ Applies to ALL public methods - use carefully

### How Spring Transactions Work (Proxy)

Spring uses **AOP proxies** to manage transactions. The proxy intercepts method calls:

```
┌─────────────────────────────────────────────────────────┐
│                    Spring Container                      │
│                                                          │
│   Client ──► Proxy ──► Actual Service Bean              │
│                │                                         │
│                ▼                                         │
│        ┌──────────────┐                                 │
│        │ 1. Begin TX  │                                 │
│        │ 2. Call Bean │                                 │
│        │ 3. Commit/   │                                 │
│        │    Rollback  │                                 │
│        └──────────────┘                                 │
└─────────────────────────────────────────────────────────┘
```

**Flow:**
1. Client calls service method
2. Call intercepted by **proxy** (not real service)
3. Proxy starts transaction
4. Proxy delegates to real service method
5. Proxy commits on success, rolls back on exception

---

## @Transactional Attributes

### propagation

Defines how transactions relate when methods call other methods.

**Attributes:**
- `REQUIRED` (default): Join existing transaction or create new one
- `REQUIRES_NEW`: Always create new transaction, suspend existing
- `NESTED`: Create savepoint within existing transaction
- `SUPPORTS`: Join if exists, otherwise run non-transactional
- `NOT_SUPPORTED`: Suspend existing, run non-transactional
- `MANDATORY`: Must have existing transaction, else throw exception
- `NEVER`: Must NOT have existing transaction, else throw exception

**REQUIRED vs REQUIRES_NEW:**

```
REQUIRED (default):
┌─────────────────────────────────────────┐
│           Outer Transaction             │
│  ┌─────────────────────────────────┐   │
│  │  Method A (joins outer)         │   │
│  └─────────────────────────────────┘   │
│  ┌─────────────────────────────────┐   │
│  │  Method B (joins outer)         │   │
│  └─────────────────────────────────┘   │
│  If ANY fails → ALL rolled back        │
└─────────────────────────────────────────┘

REQUIRES_NEW:
┌─────────────────────────────────────────┐
│           Outer Transaction             │
│  ┌─────────────────────────────────┐   │
│  │  Method A (joins outer)         │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│      Separate Transaction (NEW)         │
│  ┌─────────────────────────────────┐   │
│  │  Audit Log (independent)        │   │
│  └─────────────────────────────────┘   │
│  Commits even if outer rolls back      │
└─────────────────────────────────────────┘
```

**When to use REQUIRES_NEW?**
- Audit logging that must persist even if main transaction fails
- Independent operations that shouldn't be affected by caller's transaction
- Sending notifications that shouldn't be rolled back

### isolation

Controls what data a transaction can see from other concurrent transactions.

**Attributes:**
- `DEFAULT`: Use database default (usually READ_COMMITTED)
- `READ_UNCOMMITTED`: Can read uncommitted changes (dirty reads possible)
- `READ_COMMITTED`: Only read committed changes (prevents dirty reads)
- `REPEATABLE_READ`: Same query returns same results within transaction
- `SERIALIZABLE`: Full isolation, transactions execute sequentially

| Isolation Level | Dirty Read | Non-Repeatable Read | Phantom Read |
|-----------------|------------|---------------------|--------------|
| READ_UNCOMMITTED | Possible | Possible | Possible |
| READ_COMMITTED | Prevented | Possible | Possible |
| REPEATABLE_READ | Prevented | Prevented | Possible |
| SERIALIZABLE | Prevented | Prevented | Prevented |

**Concurrency Problems Explained:**

**Dirty Read** - Reading uncommitted changes:
```
T1: UPDATE balance = 500  (not committed)
T2: SELECT balance → reads 500 (dirty!)
T1: ROLLBACK
Reality: balance was never 500
```

**Non-Repeatable Read** - Same query, different results:
```
T1: SELECT balance → 1000
T2: UPDATE balance = 500; COMMIT
T1: SELECT balance → 500 (different!)
```

**Phantom Read** - New rows appear:
```
T1: SELECT COUNT(*) WHERE status='PENDING' → 5 rows
T2: INSERT new pending record; COMMIT
T1: SELECT COUNT(*) WHERE status='PENDING' → 6 rows (phantom!)
```

### readOnly

Hints to database that no writes will occur.

**Attributes:**
- `true`: Read-only transaction, enables optimizations
- `false` (default): Normal read-write transaction

**What it does:**
- Hibernate sets flush mode to MANUAL (no dirty checking)
- Some databases can optimize read-only transactions
- Does NOT enforce read-only at database level

### timeout

Maximum time (in seconds) for transaction to complete.

**Attributes:**
- `-1` (default): No timeout
- Positive integer: Seconds before automatic rollback

### rollbackFor / noRollbackFor

Controls which exceptions trigger rollback.

**Default behavior:**
- Unchecked exceptions (RuntimeException): ✅ Rollback
- Checked exceptions: ❌ No rollback
- Errors: ✅ Rollback

**Attributes:**
- `rollbackFor`: Exception types that should trigger rollback
- `noRollbackFor`: Exception types that should NOT trigger rollback

---

## Propagation Deep Dive

### REQUIRED (Default)

```java
@Transactional
public void methodA() {
    // Creates new transaction
    methodB();  // Joins methodA's transaction
}

@Transactional
public void methodB() {
    // Uses existing transaction from methodA
    // If methodB fails, methodA also rolls back
}
```

### REQUIRES_NEW

```java
@Transactional
public void processOrder() {
    saveOrder();           // Part of outer transaction
    auditLog("Order saved");  // Separate transaction
    // If processOrder fails after auditLog, audit is NOT rolled back
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void auditLog(String message) {
    // New transaction - independent of caller
}
```

### NESTED

```java
@Transactional
public void processItems(List<Item> items) {
    for (Item item : items) {
        try {
            processItem(item);  // Savepoint created
        } catch (Exception e) {
            // Only this item's work is rolled back
            // Other items and outer transaction continue
        }
    }
}

@Transactional(propagation = Propagation.NESTED)
public void processItem(Item item) {
    // Runs within savepoint of outer transaction
}
```

**NESTED vs REQUIRES_NEW:**

| Aspect | NESTED | REQUIRES_NEW |
|--------|--------|--------------|
| New connection | No (same connection) | Yes (new connection) |
| Outer rollback | Nested also rolls back | New transaction unaffected |
| Inner rollback | Only nested rolls back | Only new transaction rolls back |
| Database support | Requires savepoint support | Always works |

---

## Rollback Behavior

### Default Rollback Rules

| Exception Type | Rollback? | Example |
|----------------|-----------|---------|
| RuntimeException | ✅ Yes | NullPointerException, IllegalArgumentException |
| Checked Exception | ❌ No | IOException, SQLException |
| Error | ✅ Yes | OutOfMemoryError |

### Customizing Rollback

```java
// Rollback on checked exception
@Transactional(rollbackFor = InsufficientFundsException.class)
public void transfer() throws InsufficientFundsException {
    // Now InsufficientFundsException triggers rollback
}

// Rollback on all exceptions
@Transactional(rollbackFor = Exception.class)
public void process() throws Exception {
    // Any exception triggers rollback
}

// Don't rollback for specific exception
@Transactional(noRollbackFor = EmailException.class)
public void processWithNotification() {
    // Email failure won't rollback transaction
}
```

---

## Common Pitfalls

### Self-Invocation (Most Common!)

- **Issue**: Calling `@Transactional` method from same class bypasses proxy
- **Solution**: Inject self or move to separate service

**The problem:**

```java
@Service
public class TransferService {

    public void processTransfers(List<Transfer> transfers) {
        for (Transfer t : transfers) {
            transfer(t);  // ❌ Direct call - bypasses proxy!
        }
    }

    @Transactional
    public void transfer(Transfer t) {
        // Transaction NOT applied when called internally!
    }
}
```

**Why it happens:** `this.transfer()` calls the actual method, not the proxy.

| Call Type | Goes Through Proxy? | Transaction Applied? |
|-----------|---------------------|---------------------|
| External (from controller) | ✅ Yes | ✅ Yes |
| Internal (this.method()) | ❌ No | ❌ No |

**Solution 1: Inject self**

```java
@Service
public class TransferService {
    @Autowired
    private TransferService self;  // Injects the proxy

    public void processTransfers(List<Transfer> transfers) {
        for (Transfer t : transfers) {
            self.transfer(t);  // ✅ Goes through proxy
        }
    }
}
```

**Solution 2: Separate service**

```java
@Service
public class TransferOrchestrator {
    @Autowired
    private TransferService transferService;

    public void processTransfers(List<Transfer> transfers) {
        for (Transfer t : transfers) {
            transferService.transfer(t);  // ✅ Goes through proxy
        }
    }
}
```

### @Transactional on Private Methods

- **Issue**: Private methods cannot be proxied
- **Solution**: Use public methods only

```java
@Transactional
private void transfer() {  // ❌ WRONG - won't work!
    // Transaction NOT applied
}

@Transactional
public void transfer() {  // ✅ CORRECT
    // Transaction applied
}
```

| Method Visibility | Proxy Works? |
|-------------------|--------------|
| public | ✅ Yes |
| protected | ❌ No |
| package-private | ❌ No |
| private | ❌ No |

### Catching Exceptions Without Re-throwing

- **Issue**: Swallowing exceptions prevents rollback
- **Solution**: Re-throw or manually mark for rollback

**The problem:**

```java
@Transactional
public void transfer() {
    try {
        debit(fromAccount, amount);
        credit(toAccount, amount);
    } catch (Exception e) {
        log.error("Transfer failed", e);
        // ❌ Exception swallowed - transaction COMMITS with partial data!
    }
}
```

**Solution 1: Re-throw**

```java
@Transactional
public void transfer() {
    try {
        debit(fromAccount, amount);
        credit(toAccount, amount);
    } catch (Exception e) {
        log.error("Transfer failed", e);
        throw e;  // ✅ Re-throw triggers rollback
    }
}
```

**Solution 2: Manual rollback**

```java
@Transactional
public void transfer() {
    try {
        debit(fromAccount, amount);
        credit(toAccount, amount);
    } catch (Exception e) {
        log.error("Transfer failed", e);
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();  // ✅
    }
}
```

### Checked Exceptions Don't Rollback

- **Issue**: Checked exceptions don't trigger rollback by default
- **Solution**: Use `rollbackFor` attribute

```java
// ❌ WRONG - InsufficientFundsException won't trigger rollback
@Transactional
public void transfer() throws InsufficientFundsException {
    if (balance < amount) {
        throw new InsufficientFundsException();  // No rollback!
    }
}

// ✅ CORRECT - explicitly include checked exception
@Transactional(rollbackFor = InsufficientFundsException.class)
public void transfer() throws InsufficientFundsException {
    if (balance < amount) {
        throw new InsufficientFundsException();  // Rollback!
    }
}
```

### Transaction Not Starting

- **Issue**: No transaction despite `@Transactional`
- **Possible causes:**
  - Method is not public
  - Self-invocation (calling from same class)
  - Missing `@EnableTransactionManagement` (Spring Boot auto-configures this)
  - Proxy mode mismatch (using interface proxy but calling concrete class)

---

## Transaction in Multi-layer Architecture

```
┌──────────────────────────────────────────────────────────┐
│                     Controller Layer                      │
│                   (No @Transactional)                     │
│                                                           │
│   • Receives HTTP requests                               │
│   • Delegates to service layer                           │
│   • Returns HTTP responses                               │
└────────────────────────────┬─────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────┐
│                     Service Layer                         │
│                 @Transactional HERE ✅                    │
│                                                           │
│   • Contains business logic                              │
│   • Orchestrates multiple repository calls               │
│   • Single transaction spans all operations              │
└────────────────────────────┬─────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────┐
│                    Repository Layer                       │
│           (Participates in service's transaction)         │
│                                                           │
│   • Executes database queries                            │
│   • No transaction boundary here                         │
└──────────────────────────────────────────────────────────┘
```

---

## Programmatic Transaction Management

### Using TransactionTemplate

```java
@Service
public class TransferService {

    @Autowired
    private TransactionTemplate transactionTemplate;

    public void transfer(Long fromId, Long toId, BigDecimal amount) {
        transactionTemplate.execute(status -> {
            Account from = accountRepository.findById(fromId).orElseThrow();
            Account to = accountRepository.findById(toId).orElseThrow();

            from.debit(amount);
            to.credit(amount);

            return null;
        });
    }
}
```

### Using PlatformTransactionManager

```java
@Autowired
private PlatformTransactionManager transactionManager;

public void transfer() {
    TransactionDefinition def = new DefaultTransactionDefinition();
    TransactionStatus status = transactionManager.getTransaction(def);

    try {
        // database operations
        transactionManager.commit(status);
    } catch (Exception e) {
        transactionManager.rollback(status);
        throw e;
    }
}
```

| Approach | When to Use |
|----------|-------------|
| `@Transactional` | Most cases - clean and simple |
| `TransactionTemplate` | Need programmatic control within method |
| `PlatformTransactionManager` | Full low-level control needed |

---

## Testing Transactions

```java
@SpringBootTest
@Transactional  // Each test runs in transaction
class TransferServiceTest {

    @Test
    void testTransfer() {
        // Test runs in transaction
        // Automatically rolled back after test
        // Database unchanged
    }

    @Test
    @Rollback(false)  // Or @Commit
    void testThatNeedsCommit() {
        // This test commits changes
    }
}
```

| Annotation | Behavior |
|------------|----------|
| `@Transactional` on test | Runs in transaction, rolled back after |
| `@Rollback(false)` | Commits instead of rollback |
| `@Commit` | Same as `@Rollback(false)` |

---

## Database Schema

```sql
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(20) UNIQUE NOT NULL,
    account_holder VARCHAR(100) NOT NULL,
    balance DECIMAL(15,2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_accounts_account_number ON accounts(account_number);
```

---

## Sample Data

```sql
INSERT INTO accounts (account_number, account_holder, balance)
VALUES
    ('ACC001', 'Alice Johnson', 1000.00),
    ('ACC002', 'Bob Smith', 500.00),
    ('ACC003', 'Charlie Brown', 2500.00);
```

---

## Declarative vs Programmatic Comparison

| Aspect | Declarative (@Transactional) | Programmatic (TransactionTemplate) |
|--------|------------------------------|-----------------------------------|
| Boilerplate | Minimal | More verbose |
| Flexibility | Limited to method level | Fine-grained control |
| Readability | Clean, annotation-based | Explicit transaction boundaries |
| Use case | Most business methods | Complex transaction logic |
| Testing | Easy to mock | Requires more setup |

---

## Swagger UI

Access the API documentation at:
```
http://localhost:8080/bank-transfer/v1/swagger-ui.html
```
