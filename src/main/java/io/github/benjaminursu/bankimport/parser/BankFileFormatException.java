package io.github.benjaminursu.bankimport.parser;

/**
 * The file does not match the expected bank format.
 * <p>
 * The message says <em>where</em> and <em>what kind</em> of problem ("line 7: balance does not
 * match"), never the contents of the row, because messages end up in logs and HTTP responses.
 */
public class BankFileFormatException extends RuntimeException {

    private final int line;

    /** @param line 1-based line number in the file, or 0 if the problem is not tied to one line */
    public BankFileFormatException(int line, String message) {
        super(line > 0 ? "line " + line + ": " + message : message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
