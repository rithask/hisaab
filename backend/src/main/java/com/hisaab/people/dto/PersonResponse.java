package com.hisaab.people.dto;

import java.math.BigDecimal;

/**
 * @param owed        their shares of what I paid for
 * @param paid        what they paid me back
 * @param paidForMe   my shares of what they paid for
 * @param paidBack    what I paid them back
 * @param outstanding what they owe me: negative when I owe them
 */
public record PersonResponse(Long id, String name, String upiName, String phone, String notes,
                             BigDecimal owed, BigDecimal paid, BigDecimal paidForMe, BigDecimal paidBack,
                             BigDecimal outstanding) {}
