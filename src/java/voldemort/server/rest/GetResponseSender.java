package voldemort.server.rest;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LOCATION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TRANSFER_ENCODING;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.util.List;

import javax.mail.MessagingException;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponse;

import voldemort.coordinator.CoordinatorUtils;
import voldemort.utils.ByteArray;
import voldemort.versioning.VectorClock;
import voldemort.versioning.Versioned;

public class GetResponseSender extends RestResponseSender {

    private List<Versioned<byte[]>> versionedValues;
    private ByteArray key;
    private String storeName;
    private final static Logger logger = Logger.getLogger(GetResponseSender.class);

    public GetResponseSender(MessageEvent messageEvent,
                             ByteArray key,
                             List<Versioned<byte[]>> versionedValues,
                             String storeName) {
        super(messageEvent);
        this.versionedValues = versionedValues;
        this.key = key;
        this.storeName = storeName;
    }

    /**
     * Sends a multipart response. Each body part represents a versioned value
     * of the given key.
     */
    @Override
    public void sendResponse() throws Exception {

        MimeMultipart multiPart = new MimeMultipart();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        String contentLocationKey = "/" + this.storeName + "/"
                                    + new String(Base64.encodeBase64(key.get()));

        for(Versioned<byte[]> versionedValue: versionedValues) {

            byte[] responseValue = versionedValue.getValue();

            VectorClock vectorClock = (VectorClock) versionedValue.getVersion();
            String eTag = CoordinatorUtils.getSerializedVectorClock(vectorClock);

            // Create the individual body part for each versioned value of the
            // requested key
            MimeBodyPart body = new MimeBodyPart();
            try {
                // Add the right headers
                body.addHeader(CONTENT_TYPE, "application/octet-stream");
                body.addHeader(CONTENT_TRANSFER_ENCODING, "binary");
                body.addHeader(RestMessageHeaders.X_VOLD_VECTOR_CLOCK, eTag);
                body.setContent(responseValue, "application/octet-stream");
                multiPart.addBodyPart(body);
            } catch(MessagingException me) {
                logger.error("Exception while constructing body part", me);
                outputStream.close();
                throw me;
            }

        }
        try {
            multiPart.writeTo(outputStream);
        } catch(Exception e) {
            logger.error("Exception while writing multipart to output stream", e);
            outputStream.close();
            throw e;
        }
        ChannelBuffer responseContent = ChannelBuffers.dynamicBuffer();
        responseContent.writeBytes(outputStream.toByteArray());

        // Create the Response object
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);

        // Set the right headers
        response.setHeader(CONTENT_TYPE, "multipart/binary");
        response.setHeader(CONTENT_TRANSFER_ENCODING, "binary");
        response.setHeader(CONTENT_LOCATION, contentLocationKey);

        // Copy the data into the payload
        response.setContent(responseContent);
        response.setHeader(CONTENT_LENGTH, response.getContent().readableBytes());

        // Write the response to the Netty Channel
        this.messageEvent.getChannel().write(response);

        outputStream.close();

    }
}
