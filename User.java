public class User extends Thread {

    private final String        username;
    private final String        category;
    private final String        userType;
    private final BookingSystem system;

    public User(String username, String pass, String category,
                String userType, BookingSystem system) {
        this.username = username;
        this.category = category;
        this.userType = userType;
        this.system   = system;

        setDaemon(true);
        setName("BookingThread-" + username);
    }

    @Override
    public void run() {
        system.bookTicket(username, category, userType);
    }
}
