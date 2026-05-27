package es.ing.dailybanking.packagesolutionselling.core.ports.inbound.selling;

import java.util.concurrent.CompletionStage;

/**
 * Handler interface for activating package agreements.
 */
public interface PackageAgreementActivationHandler {
  CompletionStage<SellingProcessResponse> handle(PackageAgreementActivationRequest request);
}
