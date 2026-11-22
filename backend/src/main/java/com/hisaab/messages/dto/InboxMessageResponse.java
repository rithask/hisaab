package com.hisaab.messages.dto;

import com.hisaab.messages.model.Channel;
import com.hisaab.messages.model.EmailStatus;
import com.hisaab.messages.model.InboxMessage;
import com.hisaab.messages.service.SenderTrust;
import java.time.Instant;

public record InboxMessageResponse(Long id, Channel channel, String fromAddress, String subject,
                                   Instant receivedAt, EmailStatus status, String parser, String note, String bodyText,
                                   String hold, String senderDomain) {
    public static InboxMessageResponse of(InboxMessage e, boolean withBody) {
        boolean sms = e.getChannel() == Channel.SMS;
        return new InboxMessageResponse(e.getId(), e.getChannel(), e.getFromAddress(), e.getSubject(), e.getReceivedAt(), e.getStatus(),
                e.getParser(), e.getNote(), withBody || sms ? e.getBodyText() : null, e.getHold(),
                sms ? null : SenderTrust.domainOf(e.getFromAddress()));
    }
}
