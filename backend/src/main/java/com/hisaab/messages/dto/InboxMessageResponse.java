package com.hisaab.messages.dto;

import com.hisaab.messages.model.Channel;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.model.InboxMessage;
import com.hisaab.messages.service.SenderTrust;
import java.time.Instant;

public record InboxMessageResponse(Long id, Channel channel, String fromAddress, String subject,
                                   Instant receivedAt, EmailStatus status, String parser, String note, String bodyText,
                                   String hold, String senderDomain) {
    public static InboxMessageResponse of(InboxMessage message, boolean withBody) {
        boolean sms = message.getChannel() == Channel.SMS;
        return new InboxMessageResponse(message.getId(), message.getChannel(), message.getFromAddress(), message.getSubject(), message.getReceivedAt(), message.getStatus(),
                message.getParser(), message.getNote(), withBody || sms ? message.getBodyText() : null,
                        message.getHold(),
                sms ? null : SenderTrust.domainOf(message.getFromAddress()));
    }
}
