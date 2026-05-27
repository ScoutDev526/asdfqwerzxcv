# SignPackageAgreementService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the duplicated "sign and persist" 5-step sequence from `PackageAgreementActivationUseCase` and `PackageAgreementLegacyActivationUseCase` into a new `SignPackageAgreementService`. The abstract base class is untouched.

**Architecture:** A new `@Component` in `core.internal.services.selling` owns its own injected ports (`UpdateAgreementPort`, `UpdatePackageAgreementHistoryLifecycleStatusPort`, `SaveSellingProcessPort`, `SaveBenefitPlatformsNotificationsPort`, `AuditLogPort`). Each use case receives the service via constructor injection and replaces its inline 5-step chain with one delegation call. The two existing use-case behaviors observable from outside are unchanged.

**Tech Stack:** Java, Spring (`@Component`), JUnit 5, Mockito, `CompletionStage` async chaining, Maven.

## File Structure

**New:**
- `src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementService.java`
- `src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementServiceTest.java`

**Modified:**
- `src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/PackageAgreementActivationUseCase.java`
- `src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/PackageAgreementLegacyActivationUseCase.java`
- Existing tests (if present) of the two use cases: stub `signPackageAgreementService.signAndPersist(...)` instead of the 5 individual ports for the sign path.

**Unchanged:**
- `AbstractPackageAgreementActivationUseCase.java`
- `PackageAgreementActivationHandler.java`

---

### Task 1: Create SignPackageAgreementService skeleton with happy-path test

**Files:**
- Create: `src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementServiceTest.java`
- Create: `src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementService.java`

- [ ] **Step 1: Write the failing test**

Create `SignPackageAgreementServiceTest.java`:

```java
package es.ing.dailybanking.packagesolutionselling.core.internal.services.selling;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignPackageAgreementServiceTest {

  private static final String LIFECYCLE_STATUS_TYPE_ACPTD_AR = "ACPTD_AR";

  @Mock UpdateAgreementPort updateAgreementPort;
  @Mock UpdatePackageAgreementHistoryLifecycleStatusPort historyPort;
  @Mock SaveSellingProcessPort saveSellingProcessPort;
  @Mock SaveBenefitPlatformsNotificationsPort saveNotificationsPort;
  @Mock AuditLogPort auditLogPort;

  @Mock SellingProcess sellingProcess;
  @Mock PackageAgreementIdentifier packageAgreementId;
  @Mock InvolvedPartyIdentifier involvedPartyId;
  @Mock AgreementIdentifier agreementIdentifier;
  @Mock PackageType packageType;
  @Mock PackageBusinessCode packageBusinessCode;
  @Mock SellingProcessId sellingProcessId;
  @Mock ProcessType processType;
  @Mock ProcessBusinessCode processBusinessCode;
  @Mock SellingProcessStatus status;

  SignPackageAgreementService service;

  @BeforeEach
  void setUp() {
    service = new SignPackageAgreementService(
        updateAgreementPort, historyPort, saveSellingProcessPort,
        saveNotificationsPort, auditLogPort);

    when(sellingProcess.getPackageAgreementId()).thenReturn(packageAgreementId);
    when(sellingProcess.getInvolvedPartyId()).thenReturn(involvedPartyId);
    when(sellingProcess.getPackageType()).thenReturn(packageType);
    when(packageType.getPackageBusinessCode()).thenReturn(packageBusinessCode);
    when(packageBusinessCode.name()).thenReturn("PKG_X");
    when(packageAgreementId.id()).thenReturn("pkg-id");
    when(involvedPartyId.id()).thenReturn("party-id");
    when(sellingProcess.getId()).thenReturn(sellingProcessId);
    when(sellingProcessId.id()).thenReturn("sp-id");
    when(sellingProcess.getProcessType()).thenReturn(processType);
    when(processType.getBusinessCode()).thenReturn(processBusinessCode);
    when(processBusinessCode.name()).thenReturn("PROC_X");
    when(sellingProcess.getStatus()).thenReturn(status);
    when(status.name()).thenReturn("STATUS_X");
    when(sellingProcess.getCreatedAt()).thenReturn(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    when(sellingProcess.getUpdatedAt()).thenReturn(java.time.Instant.parse("2026-01-02T00:00:00Z"));
  }

  @Test
  void signAndPersist_runs_all_five_steps_in_order_and_returns_response() {
    when(updateAgreementPort.updateAgreementLifecycleStatus(
            packageAgreementId, LIFECYCLE_STATUS_TYPE_ACPTD_AR))
        .thenReturn(completedFuture(agreementIdentifier));
    when(historyPort.updatePackageAgreementHistoryLifecycleStatus(
            agreementIdentifier, LIFECYCLE_STATUS_TYPE_ACPTD_AR))
        .thenReturn(completedFuture(null));
    when(saveSellingProcessPort.saveSellingProcess(sellingProcess))
        .thenReturn(completedFuture(null));
    when(saveNotificationsPort.saveBenefitPlatformsNotifications(any()))
        .thenReturn(completedFuture(null));
    when(auditLogPort.registerAuditLog(any()))
        .thenReturn(completedFuture(null));

    SellingProcessResponse response = service.signAndPersist(
            sellingProcess, false, false, "method-X")
        .toCompletableFuture().join();

    InOrder inOrder = inOrder(
        updateAgreementPort, historyPort, sellingProcess,
        saveSellingProcessPort, saveNotificationsPort, auditLogPort);
    inOrder.verify(updateAgreementPort).updateAgreementLifecycleStatus(
        packageAgreementId, LIFECYCLE_STATUS_TYPE_ACPTD_AR);
    inOrder.verify(historyPort).updatePackageAgreementHistoryLifecycleStatus(
        agreementIdentifier, LIFECYCLE_STATUS_TYPE_ACPTD_AR);
    inOrder.verify(sellingProcess).signPackage();
    inOrder.verify(saveSellingProcessPort).saveSellingProcess(sellingProcess);
    inOrder.verify(saveNotificationsPort).saveBenefitPlatformsNotifications(any());
    inOrder.verify(auditLogPort).registerAuditLog(any());

    assertNotNull(response);
  }
}
```

> **Note on `LIFECYCLE_STATUS_TYPE_ACPTD_AR`:** the test redefines it as a local constant for isolation. The production service references the value through the project's `Constants` class — confirm the exact import path while implementing Step 3.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl . -Dtest=SignPackageAgreementServiceTest test`
Expected: compile failure — `SignPackageAgreementService` does not exist.

- [ ] **Step 3: Create the production class**

Create `SignPackageAgreementService.java`:

```java
package es.ing.dailybanking.packagesolutionselling.core.internal.services.selling;

import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.LIFECYCLE_STATUS_TYPE_ACPTD_AR;

import java.util.concurrent.CompletionStage;

import org.springframework.stereotype.Component;

@Component
public class SignPackageAgreementService {

  private final UpdateAgreementPort updateAgreementPort;
  private final UpdatePackageAgreementHistoryLifecycleStatusPort updatePackageAgreementHistoryLifecycleStatusPort;
  private final SaveSellingProcessPort saveSellingProcessPort;
  private final SaveBenefitPlatformsNotificationsPort saveBenefitPlatformsNotificationsPort;
  private final AuditLogPort auditLogPort;

  public SignPackageAgreementService(
      UpdateAgreementPort updateAgreementPort,
      UpdatePackageAgreementHistoryLifecycleStatusPort updatePackageAgreementHistoryLifecycleStatusPort,
      SaveSellingProcessPort saveSellingProcessPort,
      SaveBenefitPlatformsNotificationsPort saveBenefitPlatformsNotificationsPort,
      AuditLogPort auditLogPort) {
    this.updateAgreementPort = updateAgreementPort;
    this.updatePackageAgreementHistoryLifecycleStatusPort = updatePackageAgreementHistoryLifecycleStatusPort;
    this.saveSellingProcessPort = saveSellingProcessPort;
    this.saveBenefitPlatformsNotificationsPort = saveBenefitPlatformsNotificationsPort;
    this.auditLogPort = auditLogPort;
  }

  public CompletionStage<SellingProcessResponse> signAndPersist(
      SellingProcess sellingProcess,
      boolean hasDocumentation,
      boolean hasIdentification,
      String securityMethod) {
    return updateAgreementPort.updateAgreementLifecycleStatus(
            sellingProcess.getPackageAgreementId(), LIFECYCLE_STATUS_TYPE_ACPTD_AR)
        .thenCompose(agreementIdentifier ->
            updatePackageAgreementHistoryLifecycleStatusPort
                .updatePackageAgreementHistoryLifecycleStatus(
                    agreementIdentifier, LIFECYCLE_STATUS_TYPE_ACPTD_AR))
        .thenCompose(unused -> {
          sellingProcess.signPackage();
          return saveSellingProcessPort.saveSellingProcess(sellingProcess);
        })
        .thenCompose(unused -> {
          BenefitPlatformsNotification notification =
              BenefitPlatformsNotification.builder()
                  .id(sellingProcess.getInvolvedPartyId())
                  .hasDocumentation(hasDocumentation)
                  .hasIdentification(hasIdentification)
                  .isDwNotified(false)
                  .isGeiiNotified(false)
                  .isBillingNotified(false)
                  .build();
          return saveBenefitPlatformsNotificationsPort.saveBenefitPlatformsNotifications(
              notification);
        })
        .thenCompose(unused -> {
          AuditLogInput input = new AuditLogInput(
              null,
              sellingProcess.getPackageType().getPackageBusinessCode().name(),
              OperationType.SIGNING,
              sellingProcess.getPackageAgreementId().id(),
              securityMethod);
          return auditLogPort.registerAuditLog(input);
        })
        .thenApply(unused -> buildResponse(sellingProcess));
  }

  private SellingProcessResponse buildResponse(SellingProcess sellingProcess) {
    return new SellingProcessResponse(
        sellingProcess.getId().id(),
        sellingProcess.getProcessType().getBusinessCode().name(),
        sellingProcess.getPackageType().getPackageBusinessCode().name(),
        sellingProcess.getStatus().name(),
        sellingProcess.getPackageAgreementId().id(),
        sellingProcess.getInvolvedPartyId().id(),
        sellingProcess.getCreatedAt().toString(),
        sellingProcess.getUpdatedAt().toString());
  }
}
```

> **Resolving imports:** add the imports for the local domain types (`SellingProcess`, `SellingProcessResponse`, `BenefitPlatformsNotification`, `AuditLogInput`, `OperationType`, the five `*Port` interfaces) from their existing packages in the codebase — IDE auto-import will find them. If `Constants.LIFECYCLE_STATUS_TYPE_ACPTD_AR` lives in a different package than the one assumed above, adjust the static import.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl . -Dtest=SignPackageAgreementServiceTest test`
Expected: PASS (`signAndPersist_runs_all_five_steps_in_order_and_returns_response`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementService.java \
        src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementServiceTest.java
git commit -m "feat(selling): add SignPackageAgreementService with happy-path test"
```

---

### Task 2: Test parameter propagation

**Files:**
- Modify: `src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementServiceTest.java`

Verifies that `hasDocumentation`, `hasIdentification` and `securityMethod` are propagated to the notification builder and the audit log input.

- [ ] **Step 1: Write the failing test**

Add to `SignPackageAgreementServiceTest`:

```java
  @Test
  void signAndPersist_propagates_flags_and_security_method() {
    when(updateAgreementPort.updateAgreementLifecycleStatus(
            packageAgreementId, LIFECYCLE_STATUS_TYPE_ACPTD_AR))
        .thenReturn(completedFuture(agreementIdentifier));
    when(historyPort.updatePackageAgreementHistoryLifecycleStatus(
            agreementIdentifier, LIFECYCLE_STATUS_TYPE_ACPTD_AR))
        .thenReturn(completedFuture(null));
    when(saveSellingProcessPort.saveSellingProcess(sellingProcess))
        .thenReturn(completedFuture(null));
    ArgumentCaptor<BenefitPlatformsNotification> notificationCaptor =
        ArgumentCaptor.forClass(BenefitPlatformsNotification.class);
    when(saveNotificationsPort.saveBenefitPlatformsNotifications(notificationCaptor.capture()))
        .thenReturn(completedFuture(null));
    ArgumentCaptor<AuditLogInput> auditCaptor =
        ArgumentCaptor.forClass(AuditLogInput.class);
    when(auditLogPort.registerAuditLog(auditCaptor.capture()))
        .thenReturn(completedFuture(null));

    service.signAndPersist(sellingProcess, true, false, "sec-method-Y")
        .toCompletableFuture().join();

    BenefitPlatformsNotification notification = notificationCaptor.getValue();
    assertEquals(involvedPartyId, notification.getId());
    assertTrue(notification.isHasDocumentation());
    assertFalse(notification.isHasIdentification());
    assertFalse(notification.isDwNotified());
    assertFalse(notification.isGeiiNotified());
    assertFalse(notification.isBillingNotified());

    AuditLogInput audit = auditCaptor.getValue();
    assertEquals(OperationType.SIGNING, audit.operationType());
    assertEquals("sec-method-Y", audit.securityMethod());
    assertEquals("pkg-id", audit.packageAgreementId());
    assertEquals("PKG_X", audit.packageBusinessCode());
  }
```

Add these imports to the test file:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.mockito.ArgumentCaptor;
```

> **Note:** the assertions assume `BenefitPlatformsNotification` exposes `getId()`, `isHasDocumentation()`, `isHasIdentification()`, `isDwNotified()`, `isGeiiNotified()`, `isBillingNotified()`. Adapt the accessor names to whatever the existing record/POJO actually exposes (e.g., `id()`, `hasDocumentation()`, etc.). Same for `AuditLogInput` accessors (`operationType()`, `securityMethod()`, `packageAgreementId()`, `packageBusinessCode()`).

- [ ] **Step 2: Run the test to verify it passes**

Run: `mvn -pl . -Dtest=SignPackageAgreementServiceTest test`
Expected: PASS (both tests).

If the test fails because the production code already passes the wrong flag, fix in place by inspecting the `BenefitPlatformsNotification.builder()` chain in `SignPackageAgreementService.signAndPersist(...)`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementServiceTest.java
git commit -m "test(selling): verify flag and security method propagation in SignPackageAgreementService"
```

---

### Task 3: Test failure propagation

**Files:**
- Modify: `src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementServiceTest.java`

Verifies that a failure in any of the 5 steps propagates exceptionally through the returned `CompletionStage`.

- [ ] **Step 1: Write the failing test**

Add to `SignPackageAgreementServiceTest`:

```java
  @Test
  void signAndPersist_propagates_failure_from_audit_step() {
    when(updateAgreementPort.updateAgreementLifecycleStatus(
            packageAgreementId, LIFECYCLE_STATUS_TYPE_ACPTD_AR))
        .thenReturn(completedFuture(agreementIdentifier));
    when(historyPort.updatePackageAgreementHistoryLifecycleStatus(
            agreementIdentifier, LIFECYCLE_STATUS_TYPE_ACPTD_AR))
        .thenReturn(completedFuture(null));
    when(saveSellingProcessPort.saveSellingProcess(sellingProcess))
        .thenReturn(completedFuture(null));
    when(saveNotificationsPort.saveBenefitPlatformsNotifications(any()))
        .thenReturn(completedFuture(null));
    RuntimeException boom = new RuntimeException("audit blew up");
    when(auditLogPort.registerAuditLog(any()))
        .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(boom));

    CompletionException ex = assertThrows(CompletionException.class,
        () -> service.signAndPersist(sellingProcess, false, false, "m")
            .toCompletableFuture().join());

    assertSame(boom, ex.getCause());
  }
```

Add these imports:

```java
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletionException;
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `mvn -pl . -Dtest=SignPackageAgreementServiceTest test`
Expected: PASS (all three tests).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementServiceTest.java
git commit -m "test(selling): verify failure propagation in SignPackageAgreementService"
```

---

### Task 4: Wire SignPackageAgreementService into PackageAgreementActivationUseCase

**Files:**
- Modify: `src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/PackageAgreementActivationUseCase.java`
- Modify (if exists): existing test for `PackageAgreementActivationUseCase`

- [ ] **Step 1: Update the existing test to expect delegation**

If a test like `PackageAgreementActivationUseCaseTest` exists, replace the stubs that previously covered the 5-step sequence (`updateAgreementPort`, `updatePackageAgreementHistoryLifecycleStatusPort`, `saveSellingProcessPort`, `saveBenefitPlatformsNotificationsPort`, `auditLogPort`) with a single stub:

```java
  @Mock SignPackageAgreementService signPackageAgreementService;
```

Inject it in the use-case constructor instantiation. In the test that previously verified the sign sequence, replace those `verify(...)` calls with:

```java
  when(signPackageAgreementService.signAndPersist(
          eq(sellingProcess), eq(false), eq(false), anyString()))
      .thenReturn(completedFuture(expectedResponse));

  // ... act ...

  ArgumentCaptor<String> securityMethodCaptor = ArgumentCaptor.forClass(String.class);
  verify(signPackageAgreementService).signAndPersist(
      eq(sellingProcess), eq(false), eq(false), securityMethodCaptor.capture());
  assertTrue(securityMethodCaptor.getValue().contains(externalReference.toString()));
```

If no test exists yet in your codebase, skip Step 1 — the production change in Step 2 is still valid.

- [ ] **Step 2: Run the existing test to verify it fails (if Step 1 was applicable)**

Run: `mvn -Dtest=PackageAgreementActivationUseCaseTest test`
Expected: FAIL — the production code still invokes the 5 ports directly.

- [ ] **Step 3: Modify the production code**

In `PackageAgreementActivationUseCase.java`:

(a) Add field and constructor parameter:

```java
private final SignPackageAgreementService signPackageAgreementService;
```

Update the constructor to accept `SignPackageAgreementService signPackageAgreementService` (place it after `getSellingProcessHierarchyPort`):

```java
public PackageAgreementActivationUseCase(
    ContractSignaturePort contractSignaturePort,
    UpdateAgreementPort updateAgreementPort,
    AuditLogPort auditLogPort,
    GetSellingProcessByIdProcessPort getSellingProcessByIdProcessPort,
    SaveSellingProcessPort saveSellingProcessPort,
    SaveBenefitPlatformsNotificationsPort saveBenefitPlatformsNotificationsPort,
    UpdatePackageAgreementHistoryLifecycleStatusPort updatePackageAgreementHistoryLifecycleStatusPort,
    GetSellingProcessHierarchyPort getSellingProcessHierarchyPort,
    SignPackageAgreementService signPackageAgreementService) {
  super(getSellingProcessByIdProcessPort, auditLogPort, saveSellingProcessPort,
      saveBenefitPlatformsNotificationsPort, updateAgreementPort,
      updatePackageAgreementHistoryLifecycleStatusPort);
  this.contractSignaturePort = contractSignaturePort;
  this.getSellingProcessHierarchyPort = getSellingProcessHierarchyPort;
  this.signPackageAgreementService = signPackageAgreementService;
}
```

(b) In `handle(...)`, replace the post-signature chain with a single delegation call. The before/after:

**BEFORE** (current chain after `.thenAccept(signatureStatusInfo -> ...)`):

```java
.thenCompose(unused -> updateProductAgreements(ctx.sellingProcess,
    LIFECYCLE_STATUS_TYPE_ACPTD_AR))
.thenCompose(agreementIdentifier ->
    updatePackageAgreementHistoryLifecycleStatusPort
        .updatePackageAgreementHistoryLifecycleStatus(agreementIdentifier,
            LIFECYCLE_STATUS_TYPE_ACPTD_AR))
.thenCompose(unused -> {
  ctx.sellingProcess.signPackage();
  return saveSellingProcess(ctx.sellingProcess);
})
.thenCompose(unused -> insertRecordToTrackBenefitPlatformsNotifications(
    ctx.sellingProcess.getInvolvedPartyId(), false, false))
.thenCompose(unused -> insertAuditOpening(ctx.sellingProcess, SIGNING,
    SECURITY_METHOD_NONE + " " + HYPHEN + " " + ctx.externalReference))
.thenApply(unused -> createSellingProcessResponse(ctx.sellingProcess))
```

**AFTER:**

```java
.thenCompose(unused -> signPackageAgreementService.signAndPersist(
    ctx.sellingProcess,
    false,
    false,
    SECURITY_METHOD_NONE + " " + HYPHEN + " " + ctx.externalReference))
```

Keep the `.whenComplete(LogWhenComplete.logWhenComplete(...))` wrapper at the end as-is.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=PackageAgreementActivationUseCaseTest test`
Expected: PASS. If no test exists, run the broader module suite:
Run: `mvn -pl . test`
Expected: PASS (no new failures).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/PackageAgreementActivationUseCase.java \
        src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/PackageAgreementActivationUseCaseTest.java 2>/dev/null
git commit -m "refactor(selling): delegate sign sequence to SignPackageAgreementService in PackageAgreementActivationUseCase"
```

(Drop the test path from the `git add` if it does not exist.)

---

### Task 5: Wire SignPackageAgreementService into PackageAgreementLegacyActivationUseCase

**Files:**
- Modify: `src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/PackageAgreementLegacyActivationUseCase.java`
- Modify (if exists): existing test for `PackageAgreementLegacyActivationUseCase`

- [ ] **Step 1: Update the existing test to expect delegation in handleNotIdentifiedFlow**

If `PackageAgreementLegacyActivationUseCaseTest` (or equivalent) exists, in the test path that drives `handleNotIdentifiedFlow`, replace the stubs that covered `updateAgreementPort`, `updatePackageAgreementHistoryLifecycleStatusPort`, `saveSellingProcessPort`, `saveBenefitPlatformsNotificationsPort` and `auditLogPort` with:

```java
  @Mock SignPackageAgreementService signPackageAgreementService;
```

and stub the call:

```java
  when(signPackageAgreementService.signAndPersist(sellingProcess, true, false, ""))
      .thenReturn(completedFuture(expectedResponse));

  // ... act ...

  verify(signPackageAgreementService).signAndPersist(sellingProcess, true, false, "");
```

Leave any existing tests that exercise `handleIdentificationFlow` (operative flow) untouched — that flow still uses the abstract's helpers and is not part of this refactor.

If no test exists yet, skip Step 1.

- [ ] **Step 2: Run the existing test to verify it fails (if Step 1 was applicable)**

Run: `mvn -Dtest=PackageAgreementLegacyActivationUseCaseTest test`
Expected: FAIL.

- [ ] **Step 3: Modify the production code**

In `PackageAgreementLegacyActivationUseCase.java`:

(a) Add field and constructor parameter:

```java
private final SignPackageAgreementService signPackageAgreementService;
```

Append `SignPackageAgreementService signPackageAgreementService` as the last constructor parameter:

```java
public PackageAgreementLegacyActivationUseCase(
    AuditLogPort auditLogPort,
    InvolvedPartyPort involvedPartyPort,
    GetSellingProcessesByInvolvedPartyAndStatusPort sellingProcessesPort,
    GetAgreementsPort agreementsPort,
    GetAssessmentsPort assessmentsPort,
    GetBenefitPlatformsNotificationsByUuidPort notificationsByUuidPort,
    NotifyDwAndGeiiService notifyDwAndGeiiService,
    DeleteBenefitPlatformsNotificationsPort deleteBenefitPlatformsNotificationsPort,
    GetSellingProcessByIdProcessPort getSellingProcessByIdProcessPort,
    SavePackageAgreementHistoryAsOperativePort savePackageAgreementHistoryAsOperativePort,
    PushPkgLifecycleChangesPort pushPkgLifecycleChangesPort,
    ScheduledExecutorService scheduler,
    NotifyBillingService notifyBillingService,
    UpdateAgreementPort updateAgreementPort,
    SaveSellingProcessPort saveSellingProcessPort,
    SaveBenefitPlatformsNotificationsPort saveBenefitPlatformsNotificationsPort,
    UpdatePackageAgreementHistoryLifecycleStatusPort updatePackageAgreementHistoryLifecycleStatusPort,
    SignPackageAgreementService signPackageAgreementService) {
  super(getSellingProcessByIdProcessPort, auditLogPort, saveSellingProcessPort,
      saveBenefitPlatformsNotificationsPort, updateAgreementPort,
      updatePackageAgreementHistoryLifecycleStatusPort);
  this.involvedPartyPort = involvedPartyPort;
  this.sellingProcessesPort = sellingProcessesPort;
  this.agreementsPort = agreementsPort;
  this.assessmentsPort = assessmentsPort;
  this.notificationsByUuidPort = notificationsByUuidPort;
  this.notifyDwAndGeiiService = notifyDwAndGeiiService;
  this.deleteBenefitPlatformsNotificationsPort = deleteBenefitPlatformsNotificationsPort;
  this.savePackageAgreementHistoryAsOperativePort = savePackageAgreementHistoryAsOperativePort;
  this.pushPkgLifecycleChangesPort = pushPkgLifecycleChangesPort;
  this.scheduler = scheduler;
  this.notifyBillingService = notifyBillingService;
  this.signPackageAgreementService = signPackageAgreementService;
}
```

(b) Collapse `handleNotIdentifiedFlow` to a single delegation. Replace the whole method body:

**BEFORE:**

```java
private CompletionStage<SellingProcessResponse> handleNotIdentifiedFlow(
    UseCaseContext ctx) {
  return updateProductAgreements(
          ctx.sellingProcess,
          LIFECYCLE_STATUS_TYPE_ACPTD_AR)
      .thenCompose(agreementIdentifier ->
          updatePackageAgreementHistoryLifecycleStatusPort
              .updatePackageAgreementHistoryLifecycleStatus(agreementIdentifier,
                  LIFECYCLE_STATUS_TYPE_ACPTD_AR))
      .thenCompose(unused -> {
        ctx.sellingProcess.signPackage();
        return saveSellingProcess(ctx.sellingProcess);
      })
      .thenCompose(ignore ->
          insertRecordToTrackBenefitPlatformsNotifications(
              ctx.involvedPartyIdentifier, true, false))
      .thenCompose(ignore -> insertAuditOpening(ctx.sellingProcess, SIGNING, ""))
      .thenApply(ignore -> createSellingProcessResponse(ctx.sellingProcess));
}
```

**AFTER:**

```java
private CompletionStage<SellingProcessResponse> handleNotIdentifiedFlow(
    UseCaseContext ctx) {
  return signPackageAgreementService.signAndPersist(
      ctx.sellingProcess, true, false, "");
}
```

Leave `handleIdentificationFlow` and every other method in this class unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=PackageAgreementLegacyActivationUseCaseTest test`
Expected: PASS. If no test exists, run the broader module suite:
Run: `mvn -pl . test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/PackageAgreementLegacyActivationUseCase.java \
        src/test/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/PackageAgreementLegacyActivationUseCaseTest.java 2>/dev/null
git commit -m "refactor(selling): collapse handleNotIdentifiedFlow to SignPackageAgreementService delegation"
```

(Drop the test path from `git add` if not applicable.)

---

### Task 6: Full module verification

- [ ] **Step 1: Run the full module test suite**

Run: `mvn -pl . test`
Expected: PASS — no test failures, no compile errors.

- [ ] **Step 2: Sanity check that the abstract is untouched**

Run: `git log --oneline -- src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/usecases/selling/AbstractPackageAgreementActivationUseCase.java`
Expected: only commits prior to this refactor (no commits from Tasks 1–5).

- [ ] **Step 3: Final commit if needed**

If any incidental fix (import order, formatting) was required during the suite run, commit it:

```bash
git add -p
git commit -m "chore(selling): incidental cleanup after refactor"
```

Otherwise, this task only verifies that nothing else needs doing.

---

## Spec Coverage

- "Encapsulate the 5 common steps in a new `@Component`" → Tasks 1–3
- "Service has its own injected ports (5 ports listed)" → Task 1, Step 3
- "Public API: `signAndPersist(SellingProcess, boolean, boolean, String) → CompletionStage<SellingProcessResponse>`" → Task 1, Step 3
- "Duplicate response-building (8 lines) in service" → Task 1, Step 3 (`buildResponse(...)`)
- "Replace 6-line chain in `PackageAgreementActivationUseCase.handle`" → Task 4
- "Collapse `handleNotIdentifiedFlow` in legacy use case" → Task 5
- "Abstract base class untouched" → Task 6, Step 2
- "Top-level `LogWhenComplete` stays per-use-case" → Task 4 (Step 3 note: "Keep the `.whenComplete(LogWhenComplete...)` wrapper")
- "New unit test for the service with order, propagation, failure" → Tasks 1, 2, 3
- "Existing use-case tests stub `signAndPersist` instead of 5 ports" → Task 4, Step 1 and Task 5, Step 1
