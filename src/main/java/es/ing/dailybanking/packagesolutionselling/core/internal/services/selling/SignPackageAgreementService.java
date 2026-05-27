package es.ing.dailybanking.packagesolutionselling.core.internal.services.selling;

import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.LIFECYCLE_STATUS_TYPE_ACPTD_AR;

import java.util.concurrent.CompletionStage;

import org.springframework.stereotype.Component;

import es.ing.dailybanking.packagesolutionselling.core.domain.AuditLogInput;
import es.ing.dailybanking.packagesolutionselling.core.domain.BenefitPlatformsNotification;
import es.ing.dailybanking.packagesolutionselling.core.domain.OperationType;
import es.ing.dailybanking.packagesolutionselling.core.domain.SellingProcess;
import es.ing.dailybanking.packagesolutionselling.core.domain.SellingProcessResponse;
import es.ing.dailybanking.packagesolutionselling.core.ports.outbound.AuditLogPort;
import es.ing.dailybanking.packagesolutionselling.core.ports.outbound.SaveBenefitPlatformsNotificationsPort;
import es.ing.dailybanking.packagesolutionselling.core.ports.outbound.SaveSellingProcessPort;
import es.ing.dailybanking.packagesolutionselling.core.ports.outbound.UpdateAgreementPort;
import es.ing.dailybanking.packagesolutionselling.core.ports.outbound.UpdatePackageAgreementHistoryLifecycleStatusPort;

/**
 * Service responsible for signing a package agreement and persisting the
 * resulting state. Orchestrates lifecycle status updates, selling process
 * persistence, benefit platform notification tracking and audit logging
 * as a single chained {@link CompletionStage} flow.
 */
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

  /**
   * Executes the sign-and-persist flow for a package agreement.
   *
   * <p>The orchestration chains the following steps:
   * <ol>
   *   <li>Update the agreement lifecycle status to {@code ACPTD_AR}.</li>
   *   <li>Update the package agreement history lifecycle status.</li>
   *   <li>Mark the selling process as signed and persist it.</li>
   *   <li>Store a tracking record for benefit platform notifications.</li>
   *   <li>Register an audit log entry for the signing operation.</li>
   * </ol>
   *
   * @param sellingProcess the selling process being signed
   * @param hasDocumentation whether documentation is provided
   * @param hasIdentification whether identification data is provided
   * @param securityMethod the security method used for the signature
   * @return a future with the resulting {@link SellingProcessResponse}
   */
  public CompletionStage<SellingProcessResponse> signAndPersist(
      SellingProcess sellingProcess,
      boolean hasDocumentation,
      boolean hasIdentification,
      String securityMethod) {
    return updateAgreementPort
        .updateAgreementLifecycleStatus(sellingProcess.getPackageAgreementId(),
            LIFECYCLE_STATUS_TYPE_ACPTD_AR)
        .thenCompose(agreementIdentifier ->
            updatePackageAgreementHistoryLifecycleStatusPort
                .updatePackageAgreementHistoryLifecycleStatus(agreementIdentifier,
                    LIFECYCLE_STATUS_TYPE_ACPTD_AR))
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
          return saveBenefitPlatformsNotificationsPort
              .saveBenefitPlatformsNotifications(notification);
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

  /**
   * Builds a response DTO from a selling process entity.
   *
   * @param sellingProcess the selling process to convert
   * @return a {@link SellingProcessResponse} containing public data
   */
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
