package ro.stoia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BroadcastNode {

    private static final Logger log = LoggerFactory.getLogger(BroadcastNode.class);

    public static void main(String[] args) throws Exception {

        try{
            log.info("TEST");
            throw new Exception("Testing to see if ERROR LEVEL msg it's appended inside the file");
        }
        catch (Exception e) {
            log.error(e.getMessage());
        }

    }
}
