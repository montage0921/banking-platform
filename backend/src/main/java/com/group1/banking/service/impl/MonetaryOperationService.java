package com.group1.banking.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.group1.banking.dto.common.ErrorResponse;
import com.group1.banking.dto.customer.AccountResponse;
import com.group1.banking.dto.customer.MonetaryOperationResponse;
import com.group1.banking.dto.customer.MonetaryRequest;
import com.group1.banking.dto.customer.OperationResult;
import com.group1.banking.dto.customer.TransactionResponse;
import com.group1.banking.dto.customer.TransferRequest;
import com.group1.banking.dto.customer.TransferResponse;
import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.IdempotencyRecord;
import com.group1.banking.entity.Transaction;
import com.group1.banking.entity.TransactionDirection;
import com.group1.banking.entity.TransactionStatus;
import com.group1.banking.entity.User;
import com.group1.banking.exception.UnauthorisedException;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.IdempotencyRecordRepository;
import com.group1.banking.repository.TransactionRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.AuthenticatedUser;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.service.AuthService;
import com.group1.banking.service.AuditService;
import com.group1.banking.entity.AuditEventType;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.enums.RoleName;

@Service
public class MonetaryOperationService {

	private static final int DESCRIPTION_MAX_LENGTH = 255;
	private static final String DEPOSIT = "DEPOSIT";
	private static final String WITHDRAW = "WITHDRAW";
	private static final String TRANSFER = "TRANSFER";

	private final AccountRepository accountRepository;
	private final TransactionRepository transactionRepository;
	private final IdempotencyRecordRepository idempotencyRecordRepository;
	private final AuthService authorizationService;
	private final JsonMapper objectMapper;
	private final UserRepository userRepository;
	private final AuditService auditService;

	public MonetaryOperationService(AccountRepository accountRepository, TransactionRepository transactionRepository,
			IdempotencyRecordRepository idempotencyRecordRepository, AuthService authorizationService,
			JsonMapper objectMapper, UserRepository userRepository, AuditService auditService) {
		this.accountRepository = accountRepository;
		this.transactionRepository = transactionRepository;
		this.idempotencyRecordRepository = idempotencyRecordRepository;
		this.authorizationService = authorizationService;
		this.objectMapper = objectMapper;
		this.userRepository = userRepository;
		this.auditService = auditService;
	}

	
	@Transactional
	public OperationResult deposit(Long accountId, MonetaryRequest request, String idempotencyKey) {

	    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
	    if (!(authentication.getPrincipal() instanceof CustomUserPrincipal principal)) {
	        return unauthorized("UNAUTHORIZED", "Authenticated user not found", null);
	    }

	    UUID userId = principal.getUserId();

	    OperationResult keyError = validateIdempotencyKey(idempotencyKey);
	    if (keyError != null) {
	        return keyError;
	    }

	    String storageKey = storageKey(userId, idempotencyKey);
	    OperationResult replay = loadReplay(storageKey);
	    if (replay != null) {
	        return replay;
	    }

	    OperationResult result = validateAccountPath(accountId);
	    if (result == null) {
	        Account account = loadAccountForOperations(accountId);
	        if (account == null) {
	            result = notFound("ACCOUNT_NOT_FOUND", "Account not found", null);
	        } else {
	            User user = userRepository.findById(userId)
	                    .orElseThrow(() -> new UnauthorisedException("UNAUTHORIZED", "User not found"));

	            boolean isAdmin = user.getRoles().stream()
	                    .anyMatch(r -> r.name().equalsIgnoreCase("BANK_ADMINISTRATOR") || r.name().equalsIgnoreCase("ROLE_BANK_ADMINISTRATOR"));

	            if (!isAdmin && !user.getCustomerId().equals(account.getCustomer().getCustomerId())) {
	                result = unauthorized("UNAUTHORIZED", "You can only deposit into your own account", null);
	            } else {
	                BigDecimal amount = validateAmount(request == null ? null : request.amount());
	                if (amount == null) {
	                    result = unprocessable("INVALID_AMOUNT",
	                            "Amount must be greater than 0 with at most 2 decimal places", "amount");
	                } else {
	                    result = validateDescription(request == null ? null : request.description());
	                    if (result == null) {
	                        account.setBalance(account.getBalance().add(amount));

	                        Transaction transaction = createTransaction(
	                                account,
	                                amount,
	                                TransactionDirection.CREDIT,
	                                TransactionStatus.SUCCESS,
	                                request.description(),
	                                "External deposit",
	                                null,
	                                idempotencyKey
	                        );

	                        accountRepository.save(account);
							transactionRepository.save(transaction);

							// Audit: deposit made
							RoleName actorRoleEnum = isAdmin ? RoleName.BANK_ADMINISTRATOR : RoleName.RETAIL_CUSTOMER;
							auditService.log(AuditEventType.DEPOSIT_MADE,
								"monetary",
								actorRoleEnum,
								userId.toString(),
								"ACCOUNT",
								String.valueOf(accountId),
								AuditOutcome.SUCCESS,
								"tx:" + transaction.getTransactionId() + ",amount:" + amount.toPlainString());

							result = ok(new MonetaryOperationResponse(
	                                "Deposit completed successfully",
	                                AccountResponse.from(account),
	                                TransactionResponse.from(transaction)
	                        ));
	                    }
	                }
	            }
	        }
	    }

	    return persistAndReturn(storageKey, idempotencyKey, userId, DEPOSIT, result);
	}
	
	@Transactional
	public OperationResult withdraw(Long accountId, MonetaryRequest request, String idempotencyKey) {

	    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
	    if (!(authentication.getPrincipal() instanceof CustomUserPrincipal principal)) {
	        return unauthorized("UNAUTHORIZED", "Authenticated user not found", null);
	    }

	    UUID userId = principal.getUserId();

	    OperationResult keyError = validateIdempotencyKey(idempotencyKey);
	    if (keyError != null) {
	        return keyError;
	    }

	    String storageKey = storageKey(userId, idempotencyKey);
	    OperationResult replay = loadReplay(storageKey);
	    if (replay != null) {
	        return replay;
	    }

	    OperationResult result = validateAccountPath(accountId);
	    if (result == null) {
	        Account account = loadAccountForOperations(accountId);
	        if (account == null) {
	            result = notFound("ACCOUNT_NOT_FOUND", "Account not found", Map.of("accountId", String.valueOf(accountId)));
	        } else {
	            User user = userRepository.findById(userId)
	                    .orElseThrow(() -> new UnauthorisedException("UNAUTHORIZED", "User not found"));

	            boolean isAdmin = user.getRoles().stream()
	                    .anyMatch(r -> r.name().equalsIgnoreCase("BANK_ADMINISTRATOR") || r.name().equalsIgnoreCase("ROLE_BANK_ADMINISTRATOR"));

	            if (!isAdmin && !user.getCustomerId().equals(account.getCustomer().getCustomerId())) {
	                result = unauthorized("UNAUTHORIZED", "You can only withdraw from your own account", null);
	            } else {
	                result = ensureDebitAllowed(account);
	                BigDecimal amount = validateAmount(request == null ? null : request.amount());
	                if (result == null && amount == null) {
	                    result = unprocessable("INVALID_AMOUNT",
	                            "Amount must be greater than 0 with at most 2 decimal places", "amount");
	                } else if (result == null) {
	                    result = validateDescription(request == null ? null : request.description());
	                    if (result == null && account.getBalance().compareTo(amount) < 0) {
	                        Transaction failedTransaction = createTransaction(
	                                account,
	                                amount,
	                                TransactionDirection.DEBIT,
	                                TransactionStatus.FAILED,
	                                request.description(),
	                                null,
	                                "Cash withdrawal",
	                                idempotencyKey
	                        );
	                        transactionRepository.save(failedTransaction);

	                        result = conflict("INSUFFICIENT_FUNDS",
	                                "Withdrawal would make balance negative", null);
	                    }

	                    if (result == null) {
	                        account.setBalance(account.getBalance().subtract(amount));

	                        Transaction transaction = createTransaction(
	                                account,
	                                amount,
	                                TransactionDirection.DEBIT,
	                                TransactionStatus.SUCCESS,
	                                request.description(),
	                                null,
	                                "Cash withdrawal",
	                                idempotencyKey
	                        );

	                        accountRepository.save(account);
							transactionRepository.save(transaction);

							// Audit: withdrawal made (success)
							RoleName actorRoleEnum = isAdmin ? RoleName.BANK_ADMINISTRATOR : RoleName.RETAIL_CUSTOMER;
							auditService.log(AuditEventType.WITHDRAWAL_MADE,
								"monetary",
								actorRoleEnum,
								userId.toString(),
								"ACCOUNT",
								String.valueOf(accountId),
								AuditOutcome.SUCCESS,
								"tx:" + transaction.getTransactionId() + ",amount:" + amount.toPlainString());

							result = ok(new MonetaryOperationResponse(
	                                "Withdrawal completed successfully",
	                                AccountResponse.from(account),
	                                TransactionResponse.from(transaction)
	                        ));
	                    }
	                }
	            }
	        }
	    }

	    return persistAndReturn(storageKey, idempotencyKey, userId, WITHDRAW, result);
	}

	@Transactional
	public OperationResult transfer(TransferRequest request, String idempotencyKey) {

	    // 1️⃣ Get authenticated user (UUID ONLY)
	    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

	    if (!(authentication.getPrincipal() instanceof CustomUserPrincipal principal)) {
	        return unauthorized("UNAUTHORIZED", "Authenticated user not found",null);
	    }

	    UUID userId = principal.getUserId();

	    // 2️⃣ Idempotency validation
	    OperationResult keyError = validateIdempotencyKey(idempotencyKey);
	    if (keyError != null) return keyError;

	    String storageKey = storageKey(userId, idempotencyKey);

	    OperationResult replay = loadReplay(storageKey);
	    if (replay != null) return replay;

	    // 3️⃣ Validate request
	    OperationResult validation = validateTransferRequest(request);
	    if (validation != null) {
	        return persistAndReturn(storageKey, idempotencyKey, userId, TRANSFER, validation);
	    }

	    // 4️⃣ Load accounts
	    Account from = loadAccountForOperations(request.fromAccountId());
	    if (from == null) {
	        return persistAndReturn(storageKey, idempotencyKey, userId, TRANSFER,
	                notFound("ACCOUNT_NOT_FOUND", "Source account not found", Map.of("accountId", String.valueOf(request.fromAccountId()))));
	    }

	    Account to = loadAccountForOperations(request.toAccountId());
	    if (to == null) {
	        return persistAndReturn(storageKey, idempotencyKey, userId, TRANSFER,
	                notFound("ACCOUNT_NOT_FOUND", "Destination account not found", null));
	    }

	    OperationResult debitGuard = ensureDebitAllowed(from);
	    if (debitGuard != null) {
	        return persistAndReturn(storageKey, idempotencyKey, userId, TRANSFER, debitGuard);
	    }

	    // 5️⃣ Authorization (admin OR owner)
	    User user = userRepository.findById(userId)
	            .orElseThrow(() -> new UnauthorisedException("UNAUTHORIZED", "User not found"));

	    boolean isAdmin = user.getRoles().stream()
	            .anyMatch(r -> r.name().equalsIgnoreCase("BANK_ADMINISTRATOR") || r.name().equalsIgnoreCase("ROLE_BANK_ADMINISTRATOR"));

	    if (!isAdmin && !user.getCustomerId().equals(from.getCustomer().getCustomerId())) {
	        return persistAndReturn(storageKey, idempotencyKey, userId, TRANSFER,
	                unauthorized("UNAUTHORIZED", "You can only transfer from your own account", null));
	    }

	    // 6️⃣ Business logic
	    BigDecimal amount = request.amount().setScale(2, RoundingMode.UNNECESSARY);

	    if (from.getBalance().compareTo(amount) < 0) {

	        Transaction failedDebit = createTransaction(
	                from, amount,
	                TransactionDirection.DEBIT,
	                TransactionStatus.FAILED,
	                request.description(),
	                null,
	                "Account " + to.getAccountId(),
	                idempotencyKey);

	        Transaction failedCredit = createTransaction(
	                to, amount,
	                TransactionDirection.CREDIT,
	                TransactionStatus.FAILED,
	                request.description(),
	                "Account " + from.getAccountId(),
	                null,
	                idempotencyKey);

	        transactionRepository.save(failedDebit);
	        transactionRepository.save(failedCredit);

	        return persistAndReturn(storageKey, idempotencyKey, userId, TRANSFER,
	                conflict("INSUFFICIENT_FUNDS", "Transfer would make balance negative", null));
	    }

	    // 7️⃣ Success transfer
	    from.setBalance(from.getBalance().subtract(amount));
	    to.setBalance(to.getBalance().add(amount));

	    Transaction debit = createTransaction(
	            from, amount,
	            TransactionDirection.DEBIT,
	            TransactionStatus.SUCCESS,
	            request.description(),
	            null,
	            "Account " + to.getAccountId(),
	            idempotencyKey);

	    Transaction credit = createTransaction(
	            to, amount,
	            TransactionDirection.CREDIT,
	            TransactionStatus.SUCCESS,
	            request.description(),
	            "Account " + from.getAccountId(),
	            null,
	            idempotencyKey);

		accountRepository.save(from);
		accountRepository.save(to);
		transactionRepository.save(debit);
		transactionRepository.save(credit);

		// Audit: funds transferred
		RoleName actorRoleEnum = isAdmin ? RoleName.BANK_ADMINISTRATOR : RoleName.RETAIL_CUSTOMER;
		auditService.log(AuditEventType.FUNDS_TRANSFERRED,
			"transfers",
			actorRoleEnum,
			userId.toString(),
			"ACCOUNT",
			from.getAccountId() + "->" + to.getAccountId(),
			AuditOutcome.SUCCESS,
			"debitTx:" + debit.getTransactionId() + ",creditTx:" + credit.getTransactionId() + ",amount:" + amount.toPlainString());

	    return persistAndReturn(storageKey, idempotencyKey, userId, TRANSFER,
	            ok(new TransferResponse(
	                    "Transfer completed successfully",
	                    AccountResponse.from(from),
	                    AccountResponse.from(to),
	                    TransactionResponse.from(debit),
	                    TransactionResponse.from(credit))));
	}

	private OperationResult loadReplay(String storageKey) {
		return idempotencyRecordRepository.findById(storageKey)
				.map(record -> new OperationResult(httpStatus(record.getResponseStatus()),
						parseBody(record.getResponseBody())))
				.orElse(null);
	}

	private OperationResult persistAndReturn(String storageKey,
            String idempotencyKey,
            UUID userId,
            String operation,
            OperationResult result) {
		IdempotencyRecord record = new IdempotencyRecord();
		record.setStorageKey(storageKey);
		record.setIdempotencyKey(idempotencyKey);
		record.setResponseStatus(result.status().value());
		record.setResponseBody(writeBody(result.body()));
		record.setCallerUserId(userId.toString());
		record.setOperationType(operation);
		idempotencyRecordRepository.save(record);
		return result;
	}

	private Account loadActiveAccount(Long accountId) {
		return accountRepository.findByAccountIdAndDeletedAtIsNull(accountId)
				.filter(account -> account.getStatus() == AccountStatus.ACTIVE).orElse(null);
	}

	private Account loadAccountForOperations(Long accountId) {
		return accountRepository.findByAccountIdAndDeletedAtIsNull(accountId)
				.filter(account -> account.getStatus() != AccountStatus.CLOSED).orElse(null);
	}

	private OperationResult ensureDebitAllowed(Account account) {
		if (account.getStatus() == AccountStatus.FROZEN) {
			return conflict(
					"ACCOUNT_TEMPORARILY_RESTRICTED",
					"This account is temporarily restricted. Please contact support.",
					null);
		}
		return null;
	}

	private OperationResult authorizeAccount(AuthenticatedUser user, Account account) {
		try {
			authorizationService.assertCanAccessAccount(user, account);
			return null;
		} catch (UnauthorisedException ex) {
			return unauthorized("UNAUTHORIZED", "Unauthorized", null);
		}
	}

	private OperationResult validateAccountPath(Long accountId) {
		if (accountId == null || accountId <= 0) {
			return badRequest("INVALID_ACCOUNT_ID", "Account ID must be greater than 0", "accountId");
		}
		return null;
	}

	private OperationResult validateTransferRequest(TransferRequest request) {
		if (request == null) {
			return badRequest("INVALID_REQUEST", "Request body is required", null);
		}
		if (request.fromAccountId() == null || request.fromAccountId() <= 0) {
			return badRequest("INVALID_ACCOUNT_ID", "Source account ID must be greater than 0", "fromAccountId");
		}
		if (request.toAccountId() == null || request.toAccountId() <= 0) {
			return badRequest("INVALID_ACCOUNT_ID", "Destination account ID must be greater than 0", "toAccountId");
		}
		if (request.fromAccountId().equals(request.toAccountId())) {
			return unprocessable("INVALID_TRANSFER_ACCOUNT", "Source and destination accounts must be different",
					"toAccountId");
		}
		BigDecimal amount = validateAmount(request.amount());
		if (amount == null) {
		    return unprocessable("INVALID_AMOUNT",
		            "Amount must be greater than 0 with at most 2 decimal places",
		            "amount");
		}
		return validateDescription(request.description());
	}

	private BigDecimal validateAmount(BigDecimal amount) {

	    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
	        return null;
	    }

	    if (amount.scale() > 2) {
	        return null;
	    }

	    return amount.setScale(2, RoundingMode.HALF_UP);
	}

	private OperationResult validateDescription(String description) {
		if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
			return unprocessable("INVALID_DESCRIPTION", "Description must be 255 characters or fewer", "description");
		}
		return null;
	}

	private OperationResult validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			return badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key header is required", "Idempotency-Key");
		}
		return null;
	}

	private String storageKey(UUID userId, String idempotencyKey) {
	    return (userId != null ? userId.toString() : "anonymous") + ":" + idempotencyKey;
	}

	private Transaction createTransaction(Account account, BigDecimal amount, TransactionDirection direction,
			TransactionStatus status, String description, String senderInfo, String receiverInfo,
			String idempotencyKey) {
		Transaction transaction = new Transaction();
		transaction.setTransactionId(UUID.randomUUID().toString());
		transaction.setAccount(account);
		transaction.setAmount(amount);
		transaction.setDirection(direction);
		transaction.setStatus(status);
		transaction.setDescription(description);
		transaction.setSenderInfo(senderInfo);
		transaction.setReceiverInfo(receiverInfo);
		transaction.setIdempotencyKey(idempotencyKey);
		return transaction;
	}

	private JsonNode parseBody(String responseBody) {
		try {
			return objectMapper.readTree(responseBody);
		} catch (JacksonException ex) {
			throw new IllegalStateException("Unable to deserialize idempotency response", ex);
		}
	}

	private String writeBody(Object body) {
		try {
			return objectMapper.writeValueAsString(body);
		} catch (JacksonException ex) {
			throw new IllegalStateException("Unable to serialize idempotency response", ex);
		}
	}

	private HttpStatus httpStatus(int statusCode) {
		return HttpStatus.valueOf(statusCode);
	}

	private OperationResult ok(Object body) {
		return new OperationResult(HttpStatus.OK, body);
	}

	private OperationResult badRequest(String code, String message, String field) {
		return new OperationResult(HttpStatus.BAD_REQUEST, new ErrorResponse(code, message, field));
	}

	private OperationResult unauthorized(String code, String message, String field) {
		return new OperationResult(HttpStatus.UNAUTHORIZED, new ErrorResponse(code, message, field));
	}

	private OperationResult notFound(String code, String message) {
		return new OperationResult(HttpStatus.NOT_FOUND, new ErrorResponse(code, message, null));
	}

	private OperationResult notFound(String code, String message, Map<String, String> field) {
		return new OperationResult(HttpStatus.NOT_FOUND, new ErrorResponse(code, message, field));
	}

	private OperationResult conflict(String code, String message, String field) {
		return new OperationResult(HttpStatus.CONFLICT, new ErrorResponse(code, message, field));
	}

	private OperationResult unprocessable(String code, String message, String field) {
		return new OperationResult(HttpStatus.UNPROCESSABLE_CONTENT, new ErrorResponse(code, message, field));
	}
}
