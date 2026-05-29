package es.ing.dailybanking.packagesolutionselling.core.internal.services.selling;

import static es.ing.dailybanking.packagesolutionselling.core.internal.usecases.selling.Constants.LIFECYCLE_STATUS_TYPE_ACPTD_AR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import es.ing.dailybanking.packagesolutionselling.core.domain.AgreementIdentifier;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignPackageAgreementServiceTest {

  private static final String SECURITY_METHOD = "OTP - external-ref";

  @Mock private UpdateAgreementPort updateAgreementPort;
  @Mock private UpdatePackageAgreementHistoryLifecycleStatusPort updatePackageAgreementHistoryLifecycleStatusPort;
  @Mock private SaveSellingProcessPort saveSellingProcessPort;
  @Mock private SaveBenefitPlatformsNotificationsPort saveBenefitPlatformsNotificationsPort;
  @Mock private AuditLogPort auditLogPort;

  @Mock(answer = Answers.RETURNS_DEEP_STUBS) private SellingProcess sellingProcess;
  @Mock private AgreementIdentifier agreementIdentifier;

  private SignPackageAgreementService service;

  @BeforeEach
  void setUp() {
    service = new SignPackageAgreementService(
        updateAgreementPort,
        updatePackageAgreementHistoryLifecycleStatusPort,
        saveSellingProcessPort,
        saveBenefitPlatformsNotificationsPort,
        auditLogPort);

    when(updateAgreementPort.updateAgreementLifecycleStatus(any(), eq(LIFECYCLE_STATUS_TYPE_ACPTD_AR)))
        .thenReturn(CompletableFuture.completedFuture(agreementIdentifier));
    when(updatePackageAgreementHistoryLifecycleStatusPort
            .updatePackageAgreementHistoryLifecycleStatus(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(saveSellingProcessPort.saveSellingProcess(any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(saveBenefitPlatformsNotificationsPort.saveBenefitPlatformsNotifications(any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(auditLogPort.registerAuditLog(any()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  @Test
  @DisplayName("ejecuta la orquestación completa de firma en orden y devuelve una respuesta")
  void executesFullOrchestrationInOrder() {
    SellingProcessResponse response =
        service.signAndPersist(sellingProcess, true, true, SECURITY_METHOD)
            .toCompletableFuture().join();

    assertNotNull(response);

    InOrder inOrder = Mockito.inOrder(
        updateAgreementPort,
        updatePackageAgreementHistoryLifecycleStatusPort,
        sellingProcess,
        saveSellingProcessPort,
        saveBenefitPlatformsNotificationsPort,
        auditLogPort);
    inOrder.verify(updateAgreementPort)
        .updateAgreementLifecycleStatus(
            sellingProcess.getPackageAgreementId(), LIFECYCLE_STATUS_TYPE_ACPTD_AR);
    inOrder.verify(updatePackageAgreementHistoryLifecycleStatusPort)
        .updatePackageAgreementHistoryLifecycleStatus(
            agreementIdentifier, LIFECYCLE_STATUS_TYPE_ACPTD_AR);
    inOrder.verify(sellingProcess).signPackage();
    inOrder.verify(saveSellingProcessPort).saveSellingProcess(sellingProcess);
    inOrder.verify(saveBenefitPlatformsNotificationsPort)
        .saveBenefitPlatformsNotifications(any(BenefitPlatformsNotification.class));
    inOrder.verify(auditLogPort).registerAuditLog(any(AuditLogInput.class));
  }

  @Test
  @DisplayName("construye la notificación con el involvedParty y los flags recibidos, con notificaciones a false")
  void buildsNotificationWithExpectedFields() {
    service.signAndPersist(sellingProcess, true, false, SECURITY_METHOD)
        .toCompletableFuture().join();

    ArgumentCaptor<BenefitPlatformsNotification> captor =
        ArgumentCaptor.forClass(BenefitPlatformsNotification.class);
    verify(saveBenefitPlatformsNotificationsPort)
        .saveBenefitPlatformsNotifications(captor.capture());

    BenefitPlatformsNotification notification = captor.getValue();
    assertNotNull(notification);
    assertSame(sellingProcess.getInvolvedPartyId(), ReflectionTestUtils.getField(notification, "id"));
    assertEquals(true, ReflectionTestUtils.getField(notification, "hasDocumentation"));
    assertEquals(false, ReflectionTestUtils.getField(notification, "hasIdentification"));
    assertEquals(false, ReflectionTestUtils.getField(notification, "isDwNotified"));
    assertEquals(false, ReflectionTestUtils.getField(notification, "isGeiiNotified"));
    assertEquals(false, ReflectionTestUtils.getField(notification, "isBillingNotified"));
  }

  @Test
  @DisplayName("registra la auditoría con operación SIGNING y el security method recibido")
  void registersAuditLogWithSigningAndSecurityMethod() {
    service.signAndPersist(sellingProcess, false, false, SECURITY_METHOD)
        .toCompletableFuture().join();

    ArgumentCaptor<AuditLogInput> captor = ArgumentCaptor.forClass(AuditLogInput.class);
    verify(auditLogPort).registerAuditLog(captor.capture());

    AuditLogInput input = captor.getValue();
    assertNotNull(input);
    assertEquals(OperationType.SIGNING, input.operationType());
    assertEquals(SECURITY_METHOD, input.securityMethod());
  }

  @Test
  @DisplayName("propaga el fallo del updateAgreementPort y no ejecuta los pasos posteriores")
  void propagatesFailureAndSkipsRemainingSteps() {
    RuntimeException expected = new RuntimeException("update failed");
    when(updateAgreementPort.updateAgreementLifecycleStatus(any(), any()))
        .thenReturn(CompletableFuture.failedFuture(expected));

    CompletionStage<SellingProcessResponse> stage =
        service.signAndPersist(sellingProcess, false, false, SECURITY_METHOD);

    CompletionException thrown =
        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    assertSame(expected, thrown.getCause());

    verify(updatePackageAgreementHistoryLifecycleStatusPort, never())
        .updatePackageAgreementHistoryLifecycleStatus(any(), any());
    verify(sellingProcess, never()).signPackage();
    verify(saveSellingProcessPort, never()).saveSellingProcess(any());
    verify(saveBenefitPlatformsNotificationsPort, never())
        .saveBenefitPlatformsNotifications(any());
    verify(auditLogPort, never()).registerAuditLog(any());
  }
}
