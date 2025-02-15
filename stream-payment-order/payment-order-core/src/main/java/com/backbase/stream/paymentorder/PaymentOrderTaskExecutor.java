package com.backbase.stream.paymentorder;

import com.backbase.dbs.paymentorder.api.service.v2.PaymentOrdersApi;
import com.backbase.dbs.paymentorder.api.service.v2.model.GetPaymentOrderResponse;
import com.backbase.dbs.paymentorder.api.service.v2.model.PaymentOrderPostFilterRequest;
import com.backbase.dbs.paymentorder.api.service.v2.model.PaymentOrderPostFilterResponse;
import com.backbase.dbs.paymentorder.api.service.v2.model.PaymentOrderPostRequest;
import com.backbase.dbs.paymentorder.api.service.v2.model.PaymentOrderPostResponse;
import com.backbase.dbs.paymentorder.api.service.v2.model.PaymentOrderPutRequest;
import com.backbase.dbs.paymentorder.api.service.v2.model.Status;
import com.backbase.dbs.paymentorder.api.service.v2.model.UpdateStatusPut;
import com.backbase.stream.mappers.PaymentOrderTypeMapper;
import com.backbase.stream.model.PaymentOrderIngestContext;
import com.backbase.stream.worker.StreamTaskExecutor;
import com.backbase.stream.worker.exception.StreamTaskException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.backbase.dbs.paymentorder.api.service.v2.model.Status.*;

@Slf4j
@RequiredArgsConstructor
public class PaymentOrderTaskExecutor implements StreamTaskExecutor<PaymentOrderTask> {

    private final PaymentOrdersApi paymentOrdersApi;
    private final PaymentOrderTypeMapper paymentOrderTypeMapper;

    @Override
    public Mono<PaymentOrderTask> executeTask(PaymentOrderTask streamTask) {

        PaymentOrderIngestContext paymentOrderIngestContext = new PaymentOrderIngestContext();
        List<PaymentOrderPostRequest> corePaymentOrderPostRequestList = streamTask.getData();
        paymentOrderIngestContext.corePaymentOrder(corePaymentOrderPostRequestList);
        paymentOrderIngestContext.internalUserId(corePaymentOrderPostRequestList.get(0).getInternalUserId());

        String externalIds = streamTask.getData().stream().map(PaymentOrderPostRequest::getBankReferenceId)
                .collect(Collectors.joining(","));

        return buildPaymentOrderIngestContext(paymentOrderIngestContext)
                .flatMap(this::persistNewPaymentOrders)
                .flatMap(this::updatePaymentOrder)
                .flatMap(this::deletePaymentOrder)
                .onErrorResume(WebClientResponseException.class, throwable -> {
                    streamTask.error("payments", "post", "failed", externalIds, null, throwable,
                            throwable.getResponseBodyAsString(), "Failed to ingest payment order");
                    return Mono.error(new StreamTaskException(streamTask, throwable,
                            "Failed to Ingest Payment Order: " + throwable.getMessage()));
                })
                .map(paymentOrderContext -> {
                    streamTask.error("payments", "post", "success", externalIds, paymentOrderContext.internalUserId(), "Ingested Payment Order");
                    streamTask.setResponse(paymentOrderContext);
                    return streamTask;
                });
    }

    public Mono<PaymentOrderIngestContext> buildPaymentOrderIngestContext(PaymentOrderIngestContext paymentOrderIngestContext) {

        return getPersistedScheduledTransfers(paymentOrderIngestContext)
                .flatMap(this::buildIngestPaymentOrderList)
                .map(response -> {
                    log.debug("Ingestion context successfully build and ready for add, update and delete");
                    return response;
                });
    }

    public Mono<PaymentOrderIngestContext> buildIngestPaymentOrderList(PaymentOrderIngestContext paymentOrderIngestContext) {

        List<PaymentOrderPostRequest> newPaymentOrder = new ArrayList<>();
        List<PaymentOrderPutRequest> updatePaymentOrder = new ArrayList<>();
        List<String> deletePaymentOrder = new ArrayList<>();

        // list of all the bank ref ids in core
        List<String> coreBankRefIds = new ArrayList<>();
        for (PaymentOrderPostRequest coreBankRefId : paymentOrderIngestContext.corePaymentOrder() ) {
            coreBankRefIds.add(coreBankRefId.getBankReferenceId());
        }

        // list of all the bank ref ids in DBS (existing)
        List<String> existingBankRefIds = new ArrayList<>();
        for (GetPaymentOrderResponse existingBankRefId : paymentOrderIngestContext.existingPaymentOrder() ) {
            existingBankRefIds.add(existingBankRefId.getBankReferenceId());
        }

        // build new payment list (Bank ref is in core, but not in DBS)
        paymentOrderIngestContext.corePaymentOrder().forEach(corePaymentOrder -> {

            if(!existingBankRefIds.contains(corePaymentOrder.getBankReferenceId())) {
                newPaymentOrder.add(corePaymentOrder);
            }
        });

        // build update payment list (Bank ref is in core and DBS)
        paymentOrderIngestContext.corePaymentOrder().forEach(corePaymentOrder -> {
            if(existingBankRefIds.contains(corePaymentOrder.getBankReferenceId())) {
                updatePaymentOrder.add(paymentOrderTypeMapper.mapPaymentOrderPostRequest(corePaymentOrder));
            }
        });

        // build delete payment list (Bank ref is in DBS, but not in core)
        paymentOrderIngestContext.existingPaymentOrder().forEach(existingPaymentOrder -> {

            if(!coreBankRefIds.contains(existingPaymentOrder.getBankReferenceId())) {
                deletePaymentOrder.add(existingPaymentOrder.getId());
            }
        });

        paymentOrderIngestContext.newPaymentOrder(newPaymentOrder);
        paymentOrderIngestContext.updatePaymentOrder(updatePaymentOrder);
        paymentOrderIngestContext.deletePaymentOrder(deletePaymentOrder);
        return Mono.just(paymentOrderIngestContext);
    }

    /**
     * Gets the list of payments that are persisted in DBS for a specific user.
     * The transfers have been divided by destination product type.
     *
     * @param paymentOrderIngestContext Holds all the Ingestion details.
     * @return A Mono of List of GetPaymentOrderResponse.
     */
    public @NotNull @Valid Mono<PaymentOrderIngestContext> getPersistedScheduledTransfers(PaymentOrderIngestContext paymentOrderIngestContext) {

        List<GetPaymentOrderResponse> listOfPayments = new ArrayList<>();

        return getPayments(paymentOrderIngestContext.internalUserId())
                .map(response -> {
                        listOfPayments.addAll(response.getPaymentOrders());
                    return listOfPayments;
                })
                .map(getPaymentOrderResponses -> paymentOrderIngestContext.existingPaymentOrder(getPaymentOrderResponses))
                .doOnSuccess(result ->
                        log.debug("Successfully fetched dbs scheduled payment orders"));
    }


    /**
     * Submits a new payment request to the payment order service.
     *
     * @param paymentOrderIngestContext Holds details of current payment ingestion.
     * @return The list of all the response for each request. List<PaymentOrderPostResponse>
     */
    public Mono<PaymentOrderIngestContext> persistNewPaymentOrders(
            PaymentOrderIngestContext paymentOrderIngestContext) {

        List<PaymentOrderPostRequest> paymentOrderPostRequestList = paymentOrderIngestContext.newPaymentOrder();

        return Flux.fromIterable(paymentOrderPostRequestList)
                .flatMap(this::persistNewPaymentOrder)
                .doOnNext(response -> log.debug("Saved new Payment Order: {}", response))
                .collectList()
                .map(paymentOrderPostResponses -> paymentOrderIngestContext.newPaymentOrderResponse(paymentOrderPostResponses))
                .doOnSuccess(paymentOrderResult ->
                        log.debug("Successfully persisted: {} new scheduled transfers.",
                                paymentOrderResult.newPaymentOrderResponse().size()));
    }

    /**
     * Submit request to update a payment status.
     *
     * @param paymentOrderIngestContext Holds details of current payment ingestion.
     * @return The response from the api. Mono<List<UpdateStatusPut>>
     */
    public Mono<PaymentOrderIngestContext> updatePaymentOrder(
            PaymentOrderIngestContext paymentOrderIngestContext) {

        return Flux.fromIterable(paymentOrderIngestContext.updatePaymentOrder())
                .flatMap(request -> updatePaymentOrderStatus(
                        request.getBankReferenceId(),
                        request.getStatus(),
                        request.getBankStatus(),
                        request.getNextExecutionDate()
                ))
                .doOnNext(response -> log.debug("Updated Payment Order status: {}", response))
                .collectList()
                .map(updateStatusPuts -> paymentOrderIngestContext.updatedPaymentOrderResponse(updateStatusPuts))
                .onErrorContinue((t, o) -> log.error(String.format("Update status failed: %s", o), t))
                .doOnSuccess(paymentOrderResult ->
                        log.debug("Successfully persisted: {} Payment Order updates.",
                                paymentOrderResult.updatedPaymentOrderResponse().size()));
    }

    /**
     * Submit request to update a payment status.
     *
     * @param paymentOrderIngestContext Holds details of current payment ingestion.
     * @return The response from the api. Mono<List<UpdateStatusPut>>
     */
    public Mono<PaymentOrderIngestContext> deletePaymentOrder(
            PaymentOrderIngestContext paymentOrderIngestContext) {

        return Flux.fromIterable(paymentOrderIngestContext.deletePaymentOrder())
                .flatMap(request -> deletePaymentOrder(request))
                .doOnNext(response -> log.debug("Deleted Payment Order status: {}", response))
                .collectList()
                .map(paymentOrderIngestContext::deletePaymentOrderResponse)
                .onErrorContinue((t, o) -> log.error(String.format("Update status failed: %s", o), t))
                .doOnSuccess(paymentOrderResult ->
                        log.debug("Successfully deleted items: {} Payment Order updates."));
    }

    @Override
    public Mono<PaymentOrderTask> rollBack(PaymentOrderTask streamTask) {
        return Mono.just(streamTask);
    }

    /**
     * Makes a call to the Payment Order service to update an existing payment.
     *
     * @param newPaymentOrderRequest The new payment post order request.
     * @return A Mono with the service api response.
     */
    private Mono<PaymentOrderPostResponse> persistNewPaymentOrder(PaymentOrderPostRequest newPaymentOrderRequest) {
        newPaymentOrderRequest.setId(generateTransferUniqueId());
        return paymentOrdersApi.postPaymentOrder(
                    newPaymentOrderRequest);

    }

    /**
     * Calls the payment order service to update the status of an existing payment.
     *
     * @param bankReferenceId   The bank reference id.
     * @param status            the status of the transaction
     * @param bankStatus        The bank status.
     * @param nextExecutionDate The next execution date.
     * @return A Mono with the response from the service api.
     */
    private Mono<UpdateStatusPut> updatePaymentOrderStatus(String bankReferenceId,
                                                     Status status,
                                                     String bankStatus,
                                                     LocalDate nextExecutionDate) {

        var updatePaymentStatus = new UpdateStatusPut()
                .bankReferenceId(bankReferenceId)
                .status(status)
                .bankStatus(bankStatus)
                .nextExecutionDate(nextExecutionDate);
        return paymentOrdersApi.putUpdateStatus(updatePaymentStatus);
    }

    /**
     * Calls the payment order service to update the status of an existing payment.
     *
     * @param internalPaymentOrderId   The DBS internal Payment Order id.
     * @return A Mono with the response from the service api.
     */
    private Mono<String> deletePaymentOrder(String internalPaymentOrderId) {
        paymentOrdersApi.deletePaymentOrder(internalPaymentOrderId);
        return Mono.just(internalPaymentOrderId);
    }

    /**
     * Calls the payment order service to retrieve existing payments.
     *
     * @param internalUserId   The user's internal id that came with the Payments.
     * @return A Mono with the response from the service api.
     */
    private Mono<PaymentOrderPostFilterResponse> getPayments(String internalUserId) {
        var paymentOrderPostFilterRequest = new PaymentOrderPostFilterRequest();
        paymentOrderPostFilterRequest.setStatuses(
                List.of(READY, ACCEPTED, PROCESSED, CANCELLED, REJECTED, CANCELLATION_PENDING));

        return paymentOrdersApi.postFilterPaymentOrders(
                null, null, null, null, null, null, null, null, null, null, null,
                internalUserId, null, null, Integer.MAX_VALUE, null,
                null, paymentOrderPostFilterRequest);
    }

    /**
     * Generates a unique id to use for new transfers
     * Note: fail circuit added in case validation fails for some odd reason.
     *
     * @return A UUID in string format.
     */
    public String generateTransferUniqueId() {
        String uuid;
        boolean isValid;
        final int maxTotalAttempts = 10;
        int failAttemptsCount = 0;
        do {
            uuid = UUID.randomUUID().toString();
            isValid = validateNewPaymentUuid(uuid);

            if (!isValid) {
                failAttemptsCount += 1;
                if (failAttemptsCount > maxTotalAttempts) {
                    throw new IndexOutOfBoundsException("Could not generate a new unique id transfer after several tries.");
                }
            }
        } while (!isValid);
        return uuid;
    }

    /**
     * Validates that a uuid conforms to the valid pattern.
     *
     * @param uuid The string UUID to check.
     * @return True if it is valid.
     */
    public boolean validateNewPaymentUuid(String uuid) {
        String regexUuidPattern =
                "^[\\da-fA-F]{8}-[\\da-fA-F]{4}-[1-5][\\da-fA-F]{3}-[89abAB][\\da-fA-F]{3}-[\\da-fA-F]{12}$";
        return Pattern.compile(regexUuidPattern)
                .matcher(uuid)
                .find();
    }
}
