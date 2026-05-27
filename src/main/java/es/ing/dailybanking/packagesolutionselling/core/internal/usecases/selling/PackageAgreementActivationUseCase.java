package es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling;

import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.HYPHEN;
import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.SECURITY_METHOD_NONE;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import es.ing.dailybanking.packagesolutionselling.core.internal.services.selling.SignPackageAgreementService;

/** Use case responsible for activating a package agreement. */
@Component
public class PackageAgreementActivationUseCase extends AbstractPackageAgreementActivationUseCase
    implements PackageAgreementActivationHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(PackageAgreementActivationUseCase.class);

  private final ContractSignaturePort contractSignaturePort;
  private final GetSellingProcessHierarchyPort getSellingProcessHierarchyPort;
  private final SignPackageAgreementService signPackageAgreementService;

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

  /**
   * Handles the activation request for a package agreement.
   *
   * @param request activation request containing processId
   * @return future with the resulting {@link SellingProcessResponse}
   */
  @Override
  public CompletionStage<SellingProcessResponse> handle(PackageAgreementActivationRequest request) {
    UseCaseContext ctx = new UseCaseContext();
    UUID currentProcessId = request.processId();
    logger.info("Starting PackageAgreement activation for processId {}", currentProcessId);

    UUID previousProcessId = null;
    UUID globalProcessId = getSellingProcessHierarchyPort.findSellingProcessIdByIdProcess(currentProcessId);
    if (globalProcessId != null) {
      previousProcessId = getSellingProcessHierarchyPort
          .findSellingProcessIdByGlobalIdProcessAndNotIdProcess(globalProcessId, currentProcessId);
    }
    if (previousProcessId == null) {
      ctx.sellingProcess = getSellingProcessIfValid(currentProcessId);
    } else {
      // Repesca
      logger.info("Validate previous selling process with id {} to restart the process with id {} in a replay scenario",
          previousProcessId, currentProcessId);
      ctx.sellingProcess = validatePreviousStatusToReplay(currentProcessId, previousProcessId);
    }
    if (ctx.sellingProcess.getStatus() == PSIG || ctx.sellingProcess.getStatus() == POPR) {
      return CompletableFuture.completedFuture(createSellingProcessResponse(ctx.sellingProcess));
    }
    return retrieveSignatureStatusInfo(request)
        .thenAccept(signatureStatusInfo ->
            signatureStatusInfo.ifPresentOrElse(
                statusInfo -> {
                  checkSignatureStatusResponse(statusInfo);
                  ctx.externalReference = UUID.fromString(statusInfo.externalReference());
                },
                () -> {
                  throw new DomainModelViolationException(
                      "Contract signature information not found for processId: " + request.processId());
                }))
        .thenCompose(unused -> signPackageAgreementService.signAndPersist(
            ctx.sellingProcess,
            false,
            false,
            SECURITY_METHOD_NONE + " " + HYPHEN + " " + ctx.externalReference))
        .whenComplete(
            LogWhenComplete.logWhenComplete(
                String.format("SellingProcess %s activated for processId", request.processId()),
                String.format("SellingProcess activation failed for processId %s", request.processId())));
  }

  /**
   * Validates the previous selling process status to determine if the current process
   * can be restarted in a replay (repesca) scenario. If valid, creates a new selling
   * process with the same attributes and status as the previous one, but with the
   * current process ID.
   */
  private SellingProcess validatePreviousStatusToReplay(UUID processId, UUID previousProcessId) {
    SellingProcess previousSellingProcess = getSellingProcessIfValidForReplay(previousProcessId);
    SellingProcess sellingProcess = SellingProcess.createProcess(
        previousSellingProcess.getProcessType(),
        processId,
        previousSellingProcess.getPackageType(),
        previousSellingProcess.getInvolvedPartyId());
    sellingProcess.setStatus(previousSellingProcess.getStatus());
    saveSellingProcess(sellingProcess).toCompletableFuture().join();
    return sellingProcess;
  }

  /**
   * Retrieves a selling process by ID and verifies that it is valid for package
   * agreement activation in the context of a replay (repesca) scenario.
   * Throws domain exceptions when validation fails.
   *
   * @param processId the selling process identifier
   * @return the validated selling process
   */
  protected SellingProcess getSellingProcessIfValidForReplay(UUID processId) {
    SellingProcess sellingProcess = getSellingProcessByIdProcessPort
        .findSellingProcessByIdProcess(processId);
    checkSellingProcessNotNull(processId, sellingProcess);
    if (sellingProcess.getProcessType().isNtbAgnostic()) {
      sellingProcess.checkStatusBeforeActivatePackageAgreementReplay();
    }
    return sellingProcess;
  }

  /**
   * Retrieves signature status information from the external Contract Signature API.
   *
   * @param request activation request containing processId
   * @return future with an optional {@link SignatureStatusInfo}
   */
  private CompletionStage<Optional<SignatureStatusInfo>> retrieveSignatureStatusInfo(
      PackageAgreementActivationRequest request) {
    return contractSignaturePort.retrieveContractSignatureStatusInfo(
        SellingProcessIdentifier.of(request.processId()));
  }

  /**
   * Validates the signature status information returned by the external API.
   * Ensures the response exists and is in a valid state for activation.
   *
   * @param signatureStatusInfo signature information to validate
   * @throws DomainModelViolationException if the response is missing or in an invalid state
   */
  private void checkSignatureStatusResponse(SignatureStatusInfo signatureStatusInfo) {
    if (signatureStatusInfo == null) {
      throw new DomainModelViolationException(
          "Invalid contract signature response while trying to activate");
    }
    if (!PSIG.equals(signatureStatusInfo.status())) {
      throw new DomainModelViolationException(
          String.format(
              "Invalid contract signature status while trying to activate "
                  + "package with processId: %s. Current status is %s",
              signatureStatusInfo.processId(), signatureStatusInfo.status()));
    }
  }

  /**
   * Context used to share data between activation steps.
   * Holds the selling process and optional external reference.
   */
  static class UseCaseContext {
    SellingProcess sellingProcess;
    UUID externalReference;
  }
}
