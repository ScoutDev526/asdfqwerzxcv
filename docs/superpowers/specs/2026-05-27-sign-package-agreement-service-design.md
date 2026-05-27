# SignPackageAgreementService — Design Spec

**Date:** 2026-05-27
**Status:** Approved (pending implementation plan)

## Context

Two activation use cases — `PackageAgreementActivationUseCase` (normal flow)
and `PackageAgreementLegacyActivationUseCase` (legacy flow) — duplicate a
5-step "sign and persist" sequence after validating the agreement. Both extend
`AbstractPackageAgreementActivationUseCase`, which provides shared helpers
(`getSellingProcessIfValid`, `createSellingProcessResponse`,
`updateProductAgreements`, `insertRecordToTrackBenefitPlatformsNotifications`,
`insertAuditOpening`).

The duplicated sequence differs in only three parameters:

- `hasDocumentation` (boolean)
- `hasIdentification` (boolean)
- `securityMethod` (String)

## Goal

Extract the duplicated sign-and-persist sequence into a dedicated service so
that both use cases delegate to a single, testable component. The abstract
base class is **not** modified — its helpers remain in use by
`PackageAgreementLegacyActivationUseCase.handleIdentificationFlow` (operative
flow), so they are not dead code.

Non-goals:

- Refactoring the operative flow (`handleIdentificationFlow`).
- Refactoring the limbo flow.
- Refactoring polling logic in the legacy use case.
- Touching `AbstractPackageAgreementActivationUseCase`.

## Design

### New component

`SignPackageAgreementService` — a Spring `@Component` located in
`es.ing.dailybanking.packagesolutionselling.core.internal.services.selling`.

Naming follows the convention of existing services in the module
(`NotifyDwAndGeiiService`, `NotifyBillingService`): verb + domain + `Service`.

### Dependencies (constructor-injected ports)

| Port | Used for |
|---|---|
| `UpdateAgreementPort` | Step 1 — update agreement lifecycle to ACPTD |
| `UpdatePackageAgreementHistoryLifecycleStatusPort` | Step 2 — history lifecycle update |
| `SaveSellingProcessPort` | Step 3 — persist signed selling process |
| `SaveBenefitPlatformsNotificationsPort` | Step 4 — track benefit notifications |
| `AuditLogPort` | Step 5 — audit log for SIGNING |

These ports are the same beans Spring already wires into the abstract base
class. There is no functional duplication: each port has a single
implementation, and Spring resolves the same bean in both injection points.

### Public API

```java
public CompletionStage<SellingProcessResponse> signAndPersist(
    SellingProcess sellingProcess,
    boolean hasDocumentation,
    boolean hasIdentification,
    String securityMethod);
```

### Internal flow

The method composes the 5 steps in order via `CompletionStage` chaining:

1. `updateAgreementPort.updateAgreementLifecycleStatus(
      sellingProcess.getPackageAgreementId(),
      LIFECYCLE_STATUS_TYPE_ACPTD_AR)`
2. `updatePackageAgreementHistoryLifecycleStatusPort
      .updatePackageAgreementHistoryLifecycleStatus(
          agreementIdentifier, LIFECYCLE_STATUS_TYPE_ACPTD_AR)`
3. `sellingProcess.signPackage(); saveSellingProcessPort.saveSellingProcess(sellingProcess)`
4. Build `BenefitPlatformsNotification` with `id = sellingProcess.getInvolvedPartyId()`,
   `hasDocumentation`, `hasIdentification`, and `isDwNotified=isGeiiNotified=isBillingNotified=false`,
   then `saveBenefitPlatformsNotificationsPort.saveBenefitPlatformsNotifications(...)`
5. Build `AuditLogInput` with `OperationType.SIGNING`, the package agreement id,
   the package business code, and the received `securityMethod`,
   then `auditLogPort.registerAuditLog(...)`

Final `.thenApply` builds a `SellingProcessResponse` from the (now signed)
`sellingProcess`. The response-building logic is duplicated in the service
(approximately 8 lines, accepted trade-off to avoid modifying the abstract
base class).

### Call-site changes

**`PackageAgreementActivationUseCase.handle`** — after `checkSignatureStatusResponse`,
the 6-line chain (`updateProductAgreements` → `updatePackageAgreementHistory`
→ `sign+save` → `insertRecordToTrack` → `insertAuditOpening` → `thenApply`)
is replaced with a single call:

```java
.thenCompose(unused -> signPackageAgreementService.signAndPersist(
    ctx.sellingProcess,
    false,
    false,
    SECURITY_METHOD_NONE + " " + HYPHEN + " " + ctx.externalReference))
```

**`PackageAgreementLegacyActivationUseCase.handleNotIdentifiedFlow`** — the
entire method body collapses to a single delegation:

```java
private CompletionStage<SellingProcessResponse> handleNotIdentifiedFlow(UseCaseContext ctx) {
  return signPackageAgreementService.signAndPersist(
      ctx.sellingProcess, true, false, "");
}
```

### Constructor changes

Both use cases receive `SignPackageAgreementService` as an additional
constructor parameter. Existing ports remain in their constructors because:

- They are still required by helpers inherited from
  `AbstractPackageAgreementActivationUseCase` that other flows (notably
  `handleIdentificationFlow`) continue to invoke.
- The abstract base class is not refactored as part of this work.

### Top-level logging

The `LogWhenComplete.logWhenComplete(...)` wrapper applied at the top of each
`handle()` method is **not** moved into the service. Each use case keeps its
own success/failure message strings, so the observable log output remains
unchanged.

## Error handling

Errors propagate through the `CompletionStage` chain exactly as before. No
new exception types are introduced. Behavior observable from the caller is
identical.

## Testing

### New: `SignPackageAgreementServiceTest`

Unit test with all 5 ports mocked. Covers:

- All 5 steps are invoked in order with the expected arguments.
- The `BenefitPlatformsNotification` builder receives the correct
  `hasDocumentation` and `hasIdentification` flags and `false` for the three
  notified booleans.
- The `AuditLogInput` carries `OperationType.SIGNING` and the
  `securityMethod` argument verbatim.
- The returned `SellingProcessResponse` reflects the signed selling process
  state.
- Failure in any step propagates exceptionally through the returned future.

### Existing use-case tests

Tests that previously stubbed the 5 underlying ports for the sign sequence
now stub a single `signPackageAgreementService.signAndPersist(...)` call.
This is a net simplification.

### No integration tests added

End-to-end behavior is unchanged, so existing integration coverage suffices.

## Files affected

**New:**

- `src/main/java/es/ing/dailybanking/packagesolutionselling/core/internal/services/selling/SignPackageAgreementService.java`
- `src/test/java/.../selling/SignPackageAgreementServiceTest.java`

**Modified:**

- `src/main/java/.../usecases/selling/PackageAgreementActivationUseCase.java`
- `src/main/java/.../usecases/selling/PackageAgreementLegacyActivationUseCase.java`

**Unchanged:**

- `src/main/java/.../usecases/selling/AbstractPackageAgreementActivationUseCase.java`
- `src/main/java/.../ports/inbound/selling/PackageAgreementActivationHandler.java`
