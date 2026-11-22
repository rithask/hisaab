package com.hisaab.people.dto;

import java.math.BigDecimal;

/**
 * Where I stand with someone.
 *
 * @param owed        their shares of what I paid for
 * @param paid        what they paid me back
 * @param paidForMe   my shares of what they paid for
 * @param paidBack    what I paid them back
 * @param outstanding what they owe me: positive, they owe me; negative, I owe them
 */
public record PersonBalanceResponse(BigDecimal owed, BigDecimal paid, BigDecimal paidForMe, BigDecimal paidBack, BigDecimal outstanding) {}
