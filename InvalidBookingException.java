public class InvalidBookingException extends Exception {

    public InvalidBookingException(String msg) {
        super(msg);
    }

    public InvalidBookingException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
