package com.seatflow.payment.mapper;

import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface PaymentMapper {

    PaymentResponse toResponse(Payment payment);

    @Mapping(target = "paymentId", source = "payment.id")
    @Mapping(target = "clientSecret", source = "clientSecret")
    @Mapping(target = "amount", source = "payment.amount")
    @Mapping(target = "currency", source = "payment.currency")
    @Mapping(target = "status", source = "payment.status")
    PaymentIntentResponse toIntentResponse(Payment payment, String clientSecret);
}
