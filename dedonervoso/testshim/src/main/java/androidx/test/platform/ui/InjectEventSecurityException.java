package androidx.test.platform.ui;

public class InjectEventSecurityException extends Exception {
    public InjectEventSecurityException(String message) {
        super(message);
    }

    public InjectEventSecurityException(Throwable cause) {
        super(cause);
    }

    public InjectEventSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
