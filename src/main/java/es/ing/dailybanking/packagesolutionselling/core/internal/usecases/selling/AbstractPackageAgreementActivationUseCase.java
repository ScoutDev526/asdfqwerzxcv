package es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class that defines common operations for package agreement
 * activation use cases. Provides validation, persistence, audit logging,
 * and agreement update helpers.
 */
public abstract class AbstractPackageAgreementActivationUseCase {

  protected static final Logger logger =
      LoggerFactory.getLogger(AbstractPackageAgreementActivationUseCase.class);

  protected final GetSellingProcessByIdProcessPort getSellingProcessByIdProcessPort;
  protected final AuditLogPort auditLogPort;
  protected final SaveSellingProcessPort saveSellingProcessPort;
  protected final SaveBenefitPlatformsNotificationsPort saveBenefitPlatformsNotificationsPort;
  protected final UpdateAgreementPort updateAgreementPort;
  protected final UpdatePackageAgreementHistoryLifecycleStatusPort updatePackageAgreementHistoryLifecycleStatusPort;

  protected AbstractPackageAgreementActivationUseCase(
      GetSellingProcessByIdProcessPort getSellingProcessByIdProcessPort,
      AuditLogPort auditLogPort,
      SaveSellingProcessPort saveSellingProcessPort,
      SaveBenefitPlatformsNotificationsPort saveBenefitPlatformsNotificationsPort,
      UpdateAgreementPort updateAgreementPort,
      UpdatePackageAgreementHistoryLifecycleStatusPort updatePackageAgreementHistoryLifecycleStatusPort) {
    this.auditLogPort = auditLogPort;
    this.saveSellingProcessPort = saveSellingProcessPort;
    this.saveBenefitPlatformsNotificationsPort = saveBenefitPlatformsNotificationsPort;
    this.updateAgreementPort = updateAgreementPort;
    this.getSellingProcessByIdProcessPort = getSellingProcessByIdProcessPort;
    this.updatePackageAgreementHistoryLifecycleStatusPort = updatePackageAgreementHistoryLifecycleStatusPort;
  }

  /**
   * Retrieves a selling process by ID and verifies that it is valid for package
   * agreement activation. Throws domain exceptions when validation fails.
   *
   * @param processId the selling process identifier
   * @return a CompletionStage containing the validated selling process
   */
  protected SellingProcess getSellingProcessIfValid(UUID processId) {
    SellingProcess sellingProcess = getSellingProcessByIdProcessPort
        .findSellingProcessByIdProcess(processId);
    checkSellingProcessNotNull(processId, sellingProcess);
    sellingProcess.checkStatusBeforeActivatePackageAgreement();
    return sellingProcess;
  }

  /**
   * Persists the given selling process using the corresponding port.
   *
   * @param sp the selling process to save
   * @return a CompletionStage completed when the operation finishes
   */
  protected CompletionStage<Void> saveSellingProcess(SellingProcess sp) {
    return saveSellingProcessPort.saveSellingProcess(sp);
  }

  /**
   * Builds a response DTO from a selling process entity.
   *
   * @param sellingProcess the selling process to convert
   * @return a SellingProcessResponse containing public data
   */
  protected SellingProcessResponse createSellingProcessResponse(SellingProcess sellingProcess) {
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

  /**
   * Writes an opening audit entry for a selling process operation.
   *
   * @param sellingProcess the selling process involved
   * @param operationType the operation type to audit
   * @param securityMethod the security method used
   * @return a CompletionStage completed when the audit log is written
   */
  protected CompletionStage<Void> insertAuditOpening(SellingProcess sellingProcess,
      OperationType operationType, String securityMethod) {
    AuditLogInput input = new AuditLogInput(
        null,
        sellingProcess.getPackageType().getPackageBusinessCode().name(),
        operationType,
        sellingProcess.getPackageAgreementId().id(),
        securityMethod);
    return auditLogPort.registerAuditLog(input);
  }

  /**
   * Stores a tracking record for benefit platform notifications for a given party.
   *
   * @param involvedPartyId the involved party identifier
   * @param hasDocumentation whether documentation is provided
   * @param hasIdentification whether identification data is provided
   * @return a CompletionStage completed when the notification record is stored
   */
  protected CompletionStage<Void> insertRecordToTrackBenefitPlatformsNotifications(
      InvolvedPartyIdentifier involvedPartyId,
      boolean hasDocumentation,
      boolean hasIdentification) {
    BenefitPlatformsNotification benefitPlatformsNotification =
        BenefitPlatformsNotification.builder()
            .id(involvedPartyId)
            .hasDocumentation(hasDocumentation)
            .hasIdentification(hasIdentification)
            .isDwNotified(false)
            .isGeiiNotified(false)
            .isBillingNotified(false)
            .build();
    return saveBenefitPlatformsNotificationsPort.saveBenefitPlatformsNotifications(
        benefitPlatformsNotification);
  }

  /**
   * Updates lifecycle status of product agreements linked to a selling process.
   *
   * @param sellingProcess the selling process related to the agreement
   * @param lyfecycleStatus the new lifecycle status to set
   * @return a CompletionStage containing the resulting AgreementIdentifier
   */
  protected CompletionStage<AgreementIdentifier> updateProductAgreements(
      SellingProcess sellingProcess, String lyfecycleStatus) {
    return updateAgreementPort.updateAgreementLifecycleStatus(
        sellingProcess.getPackageAgreementId(), lyfecycleStatus);
  }
}
