public class BookifyMain {

    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            BookifyGUI gui = new BookifyGUI();
            gui.setVisible(true);
        });
    }
}
