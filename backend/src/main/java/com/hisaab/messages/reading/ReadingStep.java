package com.hisaab.messages.reading;

/** One step in reading a stored message; see {@link MessageReader} for the order. */
interface ReadingStep {
    void apply(MessageReading r);
}
