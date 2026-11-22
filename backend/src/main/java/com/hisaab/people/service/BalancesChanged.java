package com.hisaab.people.service;

/**
 * Domain event: something changed what people owe me or I owe them (a split, a "they paid" entry, a person's UPI
 * name, a removed repayment). {@link SplitService} listens and looks again at recent money nobody is linked to,
 * since a payment that arrived earlier may now be someone settling up. Published by the service that made the
 * change, inside its transaction; handled right away in the same transaction.
 *
 * @param why what changed, for the log
 */
public record BalancesChanged(String why) {}
