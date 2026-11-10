package com.hisaab.web;

import com.hisaab.domain.*;
import com.hisaab.domain.Enums.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** JSON shapes returned by the API. */
public final class Dtos {
    private Dtos() {}

    public record CategoryDto(Long id, String name, CategoryKind kind, String color) {
        static CategoryDto of(Category c) {
            return c == null ? null : new CategoryDto(c.getId(), c.getName(), c.getKind(), c.getColor());
        }
    }

    public record AccountRef(Long id, String name, AccountType type, Institution institution, String last4) {
        static AccountRef of(Account a) {
            return new AccountRef(a.getId(), a.getName(), a.getType(), a.getInstitution(), a.getLast4());
        }
    }

    public record TxnDto(Long id, AccountRef account, Direction direction, BigDecimal amount,
                         BigDecimal originalAmount, String originalCurrency, boolean amountEstimated,
                         Instant occurredAt, String counterparty, String description, String referenceNo,
                         BigDecimal reportedBalance, CategoryDto category, List<String> tags, String notes,
                         TxnSource source, Long rawEmailId, boolean transfer, Long linkedTxnId,
                         AccountRef linkedAccount, BigDecimal sharedAmount) {
        static TxnDto of(Txn t, BigDecimal shared) {
            return new TxnDto(t.getId(), AccountRef.of(t.getAccount()), t.getDirection(), t.getAmount(),
                    t.getOriginalAmount(), t.getOriginalCurrency(), t.isAmountEstimated(), t.getOccurredAt(),
                    t.getCounterparty(), t.getDescription(), t.getReferenceNo(), t.getReportedBalance(),
                    CategoryDto.of(t.getCategory()),
                    t.getTags().stream().map(Tag::getName).sorted().toList(), t.getNotes(), t.getSource(),
                    t.getRawEmail() == null ? null : t.getRawEmail().getId(), t.isTransfer(),
                    t.getLinkedTxn() == null ? null : t.getLinkedTxn().getId(),
                    t.getLinkedTxn() == null ? null : AccountRef.of(t.getLinkedTxn().getAccount()), shared);
        }
    }

    public record RuleDto(Long id, RuleMatch matchType, String pattern, CategoryDto category, RuleSource source,
                          int priority, int hits) {
        static RuleDto of(CategoryRule r) {
            return new RuleDto(r.getId(), r.getMatchType(), r.getPattern(), CategoryDto.of(r.getCategory()),
                    r.getSource(), r.getPriority(), r.getHits());
        }
    }

    public record AlertDto(Long id, AlertType type, String message, AccountRef account, Long txnId,
                           BigDecimal expected, BigDecimal actual, Instant createdAt) {
        static AlertDto of(Alert a) {
            return new AlertDto(a.getId(), a.getType(), a.getMessage(),
                    a.getAccount() == null ? null : AccountRef.of(a.getAccount()),
                    a.getTxn() == null ? null : a.getTxn().getId(), a.getExpected(), a.getActual(), a.getCreatedAt());
        }
    }

    public record EmailDto(Long id, com.hisaab.domain.Enums.Channel channel, String fromAddress, String subject,
                           Instant receivedAt, EmailStatus status, String parser, String note, String bodyText) {
        static EmailDto of(RawEmail e, boolean withBody) {
            boolean sms = e.getChannel() == com.hisaab.domain.Enums.Channel.SMS;
            return new EmailDto(e.getId(), e.getChannel(), e.getFromAddress(), e.getSubject(), e.getReceivedAt(), e.getStatus(),
                    e.getParser(), e.getNote(), withBody || sms ? e.getBodyText() : null);
        }
    }

    public record StatementDto(Long id, AccountRef account, String fileName, long sizeBytes, String periodLabel,
                               Instant receivedAt, String importStatus, String importNote, boolean fromEmail) {
        static StatementDto of(StatementFile s) {
            return new StatementDto(s.getId(), s.getAccount() == null ? null : AccountRef.of(s.getAccount()),
                    s.getFileName(), s.getSizeBytes(), s.getPeriodLabel(), s.getReceivedAt(), s.getImportStatus(),
                    s.getImportNote(), s.getRawEmail() != null);
        }
    }

    public record ShareDto(Long id, Long personId, String personName, SplitMethod method, BigDecimal input,
                           BigDecimal amount) {
        static ShareDto of(SplitShare s) {
            return new ShareDto(s.getId(), s.getPerson().getId(), s.getPerson().getName(), s.getMethod(),
                    s.getInput(), s.getAmount());
        }
    }

    public record SettlementDto(Long id, Long personId, String personName, BigDecimal amount, Instant settledAt,
                                SettlementStatus status, String note, Long txnId, String txnCounterparty) {
        static SettlementDto of(Settlement s) {
            return new SettlementDto(s.getId(), s.getPerson().getId(), s.getPerson().getName(), s.getAmount(),
                    s.getSettledAt(), s.getStatus(), s.getNote(),
                    s.getTxn() == null ? null : s.getTxn().getId(),
                    s.getTxn() == null ? null : s.getTxn().getCounterparty());
        }
    }

    public record PersonDto(Long id, String name, String upiName, String phone, String notes,
                            BigDecimal owed, BigDecimal paid, BigDecimal outstanding) {}

    public record HoldingDto(Long id, AssetType assetType, String symbol, String exchange, String name,
                             BigDecimal quantity, BigDecimal avgPrice, BigDecimal lastPrice, BigDecimal prevClose,
                             Instant priceAsOf, BigDecimal invested, BigDecimal currentValue, BigDecimal pnl,
                             BigDecimal pnlPct, BigDecimal dayChange) {
        static HoldingDto of(Holding h) {
            BigDecimal invested = h.getQuantity().multiply(h.getAvgPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal current = h.getLastPrice() == null ? null
                    : h.getQuantity().multiply(h.getLastPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pnl = current == null ? null : current.subtract(invested);
            BigDecimal pct = pnl == null || invested.signum() == 0 ? null
                    : pnl.multiply(BigDecimal.valueOf(100)).divide(invested, 2, RoundingMode.HALF_UP);
            BigDecimal day = h.getLastPrice() == null || h.getPrevClose() == null ? null
                    : h.getLastPrice().subtract(h.getPrevClose()).multiply(h.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            return new HoldingDto(h.getId(), h.getAssetType(), h.getSymbol(), h.getExchange(), h.getName(),
                    h.getQuantity(), h.getAvgPrice(), h.getLastPrice(), h.getPrevClose(), h.getPriceAsOf(),
                    invested, current, pnl, pct, day);
        }
    }

    public record PortfolioDto(BigDecimal invested, BigDecimal currentValue, BigDecimal pnl, BigDecimal pnlPct,
                               BigDecimal dayChange, List<HoldingDto> holdings) {
        static PortfolioDto of(List<HoldingDto> list) {
            BigDecimal inv = BigDecimal.ZERO, cur = BigDecimal.ZERO, day = BigDecimal.ZERO;
            for (HoldingDto h : list) {
                inv = inv.add(h.invested());
                cur = cur.add(h.currentValue() == null ? h.invested() : h.currentValue());
                if (h.dayChange() != null) day = day.add(h.dayChange());
            }
            BigDecimal pnl = cur.subtract(inv);
            BigDecimal pct = inv.signum() == 0 ? BigDecimal.ZERO
                    : pnl.multiply(BigDecimal.valueOf(100)).divide(inv, 2, RoundingMode.HALF_UP);
            List<HoldingDto> sorted = list.stream()
                    .sorted(Comparator.comparing((HoldingDto h) -> h.currentValue() == null ? h.invested() : h.currentValue()).reversed())
                    .toList();
            return new PortfolioDto(inv, cur, pnl, pct, day, sorted);
        }
    }
}
