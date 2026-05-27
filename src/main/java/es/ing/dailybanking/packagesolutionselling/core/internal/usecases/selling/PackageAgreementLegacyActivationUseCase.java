package es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling;

import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.CCT_NTB_LEGACY_REGEX;
import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.LIFECYCLE_STATUS_TYPE_ACPTD_AR;
import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.LIFECYCLE_STATUS_TYPE_EFF_AR;
import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.PACKAGE_LIST;
import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.PRODUCT_TYPE_ES_CRN_AC;
import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.SECURITY_METHOD_NONE;
import static java.lang.String.format;

import java.time.Duration;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import es.ing.dailybanking.packagesolutionselling.core.internal.services.selling.SignPackageAgreementService;

/** Handles the legacy activation flow for Package Agreements. */
@Component
public class PackageAgreementLegacyActivationUseCase
    extends AbstractPackageAgreementActivationUseCase
    implements PackageAgreementActivationHandler {

  private static final Set<String> SIGNED_STATUS =
      Set.of(LIFECYCLE_STATUS_TYPE_ACPTD_AR, LIFECYCLE_STATUS_TYPE_EFF_AR);

  private static final Logger logger =
      LoggerFactory.getLogger(PackageAgreementLegacyActivationUseCase.class);

  private final InvolvedPartyPort involvedPartyPort;
  private final GetSellingProcessesByInvolvedPartyAndStatusPort sellingProcessesPort;
  private final GetAgreementsPort agreementsPort;
  private final GetAssessmentsPort assessmentsPort;
  private final GetBenefitPlatformsNotificationsByUuidPort notificationsByUuidPort;
  private final NotifyDwAndGeiiService notifyDwAndGeiiService;
  private final DeleteBenefitPlatformsNotificationsPort deleteBenefitPlatformsNotificationsPort;
  private final SavePackageAgreementHistoryAsOperativePort savePackageAgreementHistoryAsOperativePort;
  private final PushPkgLifecycleChangesPort pushPkgLifecycleChangesPort;
  private final ScheduledExecutorService scheduler;
  private final NotifyBillingService notifyBillingService;
  private final SignPackageAgreementService signPackageAgreementService;

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

  /**
   * Entry point for handling legacy package agreement activation.
   * Performs request validation, retrieves selling process or involved party,
   * and starts the activation flow including polling if needed.
   *
   * @param request activation request
   * @return asynchronous response containing the selling process result
   */
  @Override
  public CompletionStage<SellingProcessResponse> handle(PackageAgreementActivationRequest request) {
    logger.info("Starting PackageAgreement activation for processId {}", request.processId());
    UseCaseContext ctx = new UseCaseContext();
    // FSA05
    if (request.processId() == null) {
      return involvedPartyPort.retrieveInternalIdentifiers()
          .thenCompose(party -> {
            ctx.involvedPartyIdentifier = party.involvedPartyIdentifier();
            return startActivationFlow(ctx);
          })
          .whenComplete(
              LogWhenComplete.logWhenComplete(
                  "SellingProcess legacy activated",
                  "SellingProcess legacy activation failed"));
    } else {
      ctx.sellingProcess = getSellingProcessIfValid(request.processId());
      ctx.involvedPartyIdentifier = ctx.sellingProcess.getInvolvedPartyId();
      // FSA 11
      return pollCheckSigned(ctx)
          .whenComplete(
              LogWhenComplete.logWhenComplete(
                  "SellingProcess legacy activated",
                  "SellingProcess legacy activation failed"));
    }
  }

  /**
   * Polls repeatedly until an accepted/offered status is returned, or timeout occurs.
   *
   * @param ctx use case context containing involved party identifier and selling process
   * @return future returning the lifecycle status or failing with timeout
   */
  private CompletableFuture<String> pollForAcceptedOrOfferedStatus(UseCaseContext ctx) {
    return pollUntilStatusOrTimeout(
        ctx,
        Duration.ofSeconds(3),
        Duration.ofMillis(500));
  }

  /**
   * Generic polling loop that checks account status at fixed intervals until either:
   * <ul>
   *   <li>The target lifecycle status is reached</li>
   *   <li>A timeout is exceeded</li>
   * </ul>
   *
   * @param ctx use case context
   * @param timeout maximum time allowed
   * @param interval interval between polling attempts
   * @return async future with the lifecycle status
   */
  private CompletableFuture<String> pollUntilStatusOrTimeout(
      UseCaseContext ctx,
      Duration timeout,
      Duration interval
  ) {
    final CompletableFuture<String> result = new CompletableFuture<>();
    final long deadlineNanos = System.nanoTime() + timeout.toNanos();

    final Runnable[] taskRef = new Runnable[1];

    taskRef[0] = () -> checkAccountSigned(ctx).whenComplete((status, ex) -> {
      if (result.isDone()) return;

      if (ex != null) {
        result.completeExceptionally(ex);
        return;
      }

      if (status != null && SIGNED_STATUS.contains(status)) {
        result.complete(status);
        return;
      }

      if (System.nanoTime() >= deadlineNanos) {
        result.completeExceptionally(
            new TimeoutException("Timeout: status " +
                SIGNED_STATUS + " not found in " + timeout.toMillis() + " ms")
        );
      } else {
        scheduler.schedule(
            taskRef[0],
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        );
      }
    });
    scheduler.execute(taskRef[0]);
    return result;
  }

  /**
   * Performs polling for agreement status and then continues activation flow.
   * <p>
   * If status is found -> continues normal activation.<br>
   * If timeout occurs -> process continues with null status (limbo flow).
   *
   * @param ctx use case context
   * @return async selling process response
   */
  private CompletionStage<SellingProcessResponse> pollCheckSigned(UseCaseContext ctx) {
    return pollForAcceptedOrOfferedStatus(ctx)
        .thenCompose(status -> {
          logger.debug("Status {} found", status);
          return processAgreement(ctx, status);
        })
        .exceptionallyCompose(ex -> {
          if (ex.getCause().getClass().equals(TimeoutException.class)) {
            logger.warn("Status not found in 3s", ex);
            return processAgreement(ctx, null);
          }
          return CompletableFuture.failedStage(ex);
        });
  }

  /**
   * Starts activation flow when selling process must be retrieved
   * based on involved party identifier (call center ntb).
   *
   * @param ctx use case context
   * @return async response
   */
  private CompletionStage<SellingProcessResponse> startActivationFlow(UseCaseContext ctx) {
    return sellingProcessesPort.getSellingProcessesByInvolvedPartyAndStatusPort(
            ctx.involvedPartyIdentifier, PackageStatus.PCNS)
        .thenCompose(sellingProcess -> {
          validateSellingProcess(sellingProcess);
          ctx.sellingProcess = sellingProcess;
          return pollCheckSigned(ctx);
        });
  }

  /**
   * Retrieves and evaluates agreement lifecycle status for polling.
   * <p>
   * For Call center NTB packages -> exactly one agreement must exist.<br>
   * For other packages -> returns the newest agreement status if updated.
   *
   * @param ctx use case context
   * @return async lifecycle status or null if not ready
   */
  private CompletionStage<String> checkAccountSigned(UseCaseContext ctx) {
    return agreementsPort.getActiveAccountAgreementsAsHolder(
            ctx.involvedPartyIdentifier, PRODUCT_TYPE_ES_CRN_AC)
        .thenApply(agreements -> {
          if (ctx.sellingProcess.getProcessType().getBusinessCode().name()
              .matches(CCT_NTB_LEGACY_REGEX)) {
            if (agreements.size() > 1) {
              throw new DomainModelViolationException("More than one active account found");
            }
            return agreements.getFirst().lifecycleStatus();
          } else {
            ProductAgreement accountNewest =
                agreements.stream()
                    .max(Comparator.comparing(ProductAgreement::effectiveDate))
                    .orElse(null);
            if (accountNewest != null
                && accountNewest.effectiveDate().toInstant().compareTo(
                    ctx.sellingProcess.getUpdatedAt().getInstant()) > 0) {
              return accountNewest.lifecycleStatus();
            }
            return null;
          }
        });
  }

  /**
   * Executes activation or limbo logic based on lifecycle status.
   *
   * @param ctx use case context
   * @param status lifecycle status found (or null on timeout)
   * @return async selling process response
   */
  private CompletionStage<SellingProcessResponse> processAgreement(
      UseCaseContext ctx,
      String status) {

    boolean accepted = LIFECYCLE_STATUS_TYPE_ACPTD_AR.equals(status);
    boolean effective = LIFECYCLE_STATUS_TYPE_EFF_AR.equals(status);

    if (!accepted && !effective) {
      return putSellingProcessInLimbo(ctx.sellingProcess);
    }

    return assessmentsPort.isIdentified(ctx.involvedPartyIdentifier)
        .thenCompose(isIdentified -> isIdentified
            ? handleIdentificationFlow(ctx)
            : handleNotIdentifiedFlow(ctx));
  }

  /**
   * Handles the activation flow when the customer is identified.
   *
   * @param ctx the use case context containing process and customer information
   * @return a future producing the final SellingProcessResponse
   */
  private CompletionStage<SellingProcessResponse> handleIdentificationFlow(
      UseCaseContext ctx) {

    return insertRecordToTrackBenefitPlatformsNotifications(
            ctx.involvedPartyIdentifier, true, true)
        .thenCompose(unused ->
            notificationsByUuidPort.getBenefitPlatformsNotificationsByUuid(
                ctx.involvedPartyIdentifier))
        .exceptionally(err -> {
          throw new DomainNotFoundException(
              format("No package benefits for customer %s.", ctx.involvedPartyIdentifier));
        })
        .thenCompose(benefitPlatformsNotification -> notifyDwAndGeiiService.notifyDwAndGeii(
            ctx.sellingProcess.getPackageAgreementId(),
            ctx.involvedPartyIdentifier,
            ctx.sellingProcess.getPackageType().getPackageBusinessCode()))
        .thenCompose(benefitPlatformsNotification -> {
          ctx.benefitPlatformsNotification = benefitPlatformsNotification;
          return updateProductAgreements(
              ctx.sellingProcess,
              LIFECYCLE_STATUS_TYPE_EFF_AR);
        })
        .thenCompose(ignore -> {
          ctx.sellingProcess.setOperative();
          return saveSellingProcess(ctx.sellingProcess)
              .thenCompose(saveDone ->
                  savePackageAgreementHistoryAsOperativePort
                      .savePackageAgreementHistoryAsOperativePort(
                          ctx.sellingProcess.getPackageAgreementId(),
                          ctx.sellingProcess.getPackageType()))
              .thenAccept(historySaved ->
                  pushPkgLifecycleChangesPort.push(CREATED,
                      ctx.sellingProcess.getPackageAgreementId().id(),
                      ctx.involvedPartyIdentifier.id(),
                      ctx.sellingProcess.getPackageType().getPackageBusinessCode().name(),
                      ""))
              .thenCompose(pushDone ->
                  notifyBillingService.notifyBillingEngine(
                      ctx.benefitPlatformsNotification,
                      ctx.sellingProcess.getPackageAgreementId(),
                      ctx.sellingProcess.getPackageType().getPackageBusinessCode()))
              .thenCompose(benefitPlatformsNotification -> {
                if (benefitPlatformsNotification.canRemove()) {
                  return deleteBenefitPlatformsNotificationsPort
                      .deleteBenefitPlatformsNotificationsPort(
                          benefitPlatformsNotification.getId());
                }
                return CompletableFuture.completedFuture(null);
              })
              .thenCompose(billingDone ->
                  insertAuditOpening(ctx.sellingProcess, SIGNING, SECURITY_METHOD_NONE)
                      .thenCompose(audit1 ->
                          insertAuditOpening(ctx.sellingProcess, OPERATIVE, SECURITY_METHOD_NONE)));
        })
        .thenApply(unused ->
            createSellingProcessResponse(ctx.sellingProcess));
  }

  /**
   * Handles the activation flow when the customer is not identified.
   *
   * @param ctx the use case context containing customer and selling process info
   * @return a future producing the SellingProcessResponse
   */
  private CompletionStage<SellingProcessResponse> handleNotIdentifiedFlow(
      UseCaseContext ctx) {
    return signPackageAgreementService.signAndPersist(
        ctx.sellingProcess, true, false, "");
  }

  /**
   * Validates that the selling process exists and matches expected business rules.
   *
   * @param sellingProcess the selling process to validate
   * @throws DomainNotFoundException if the process is missing
   * @throws DomainModelViolationException if validation rules fail
   */
  private void validateSellingProcess(SellingProcess sellingProcess) {
    if (sellingProcess == null) {
      throw new DomainNotFoundException("Selling process not found");
    }
    boolean validCct = sellingProcess.getProcessType().getBusinessCode().name()
        .matches(CCT_NTB_LEGACY_REGEX);

    boolean validPackage = PACKAGE_LIST.contains(
        sellingProcess.getPackageType().getPackageBusinessCode().name());

    if (!validCct || !validPackage) {
      throw new DomainModelViolationException("Selling process wrong");
    }
  }

  /**
   * Moves the selling process into a limbo state when activation cannot proceed.
   * <p>
   * This persists the new state and returns the corresponding response.
   *
   * @param sellingProcess the process to move into limbo
   * @return a future producing the updated SellingProcessResponse
   */
  private CompletionStage<SellingProcessResponse> putSellingProcessInLimbo(
      SellingProcess sellingProcess) {
    sellingProcess.addLimbo();
    return saveSellingProcess(sellingProcess)
        .thenApply(ignore -> createSellingProcessResponse(sellingProcess));
  }

  /**
   * Holds contextual information shared across the activation flow.
   * <p>
   * Includes:
   * <ul>
   *   <li>The current selling process</li>
   *   <li>The involved party identifier</li>
   * </ul>
   */
  static class UseCaseContext {
    SellingProcess sellingProcess;
    InvolvedPartyIdentifier involvedPartyIdentifier;
    BenefitPlatformsNotification benefitPlatformsNotification;
  }
}
