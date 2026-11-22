package com.hisaab.common.model;

/** The bank an account is with; OTHER for any bank the app has no parsers for. */
public enum Institution {
    HDFC("HDFC Bank"), AXIS("Axis Bank"), SBI("SBI"), CANARA("Canara Bank"), OTHER("bank");

    private final String label;

    Institution(String label) {
        this.label = label;
    }

    /** How the bank is named in sentences ("isn't a known HDFC Bank address"). */
    public String label() {
        return label;
    }
}
