package com.worldpay.service.http.impl;

import com.worldpay.exception.WorldpayCommunicationException;
import com.worldpay.exception.WorldpayModelTransformationException;
import com.worldpay.internal.model.PaymentService;
import com.worldpay.service.http.ServiceReply;
import com.worldpay.service.http.WorldpayConnector;
import com.worldpay.service.marshalling.PaymentServiceMarshaller;
import com.worldpay.service.model.MerchantInfo;
import com.worldpay.util.WorldpayConstants;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import org.apache.commons.io.output.XmlStreamWriter;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Required;
import reactor.util.CollectionUtils;

import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Implementation class to make HTTP Post of messages to Worldpay. Implements the {@link WorldpayConnector} interface.
 */
public class DefaultWorldpayConnector implements WorldpayConnector {

    private static final Logger LOG = Logger.getLogger(DefaultWorldpayConnector.class);

    protected static final String WORLDPAY_CONFIG_ENDPOINT = "worldpay.config.endpoint";
    protected static final String WORLDPAY_CONFIG_ENVIRONMENT = "worldpay.config.environment";

    private PaymentServiceMarshaller paymentServiceMarshaller;
    private ConfigurationService configurationService;

    @Override
    public ServiceReply send(final PaymentService paymentService, final MerchantInfo merchantInfo, final String cookie) throws WorldpayCommunicationException, WorldpayModelTransformationException {
        final URLConnection connection = sendXML(paymentService, merchantInfo, cookie);
        return receiveXML(connection);
    }

    private ServiceReply receiveXML(final URLConnection connection) throws WorldpayCommunicationException, WorldpayModelTransformationException {
        final ServiceReply response = new ServiceReply();
        try (final InputStream in = connection.getInputStream()) {
            setCookiesOnResponse(connection, response);
            response.setPaymentService(paymentServiceMarshaller.unmarshal(in));
        } catch (IOException e) {
            throw new WorldpayCommunicationException("Unable to receive response from Worldpay", e);
        }
        return response;
    }

    private void setCookiesOnResponse(final URLConnection connection, final ServiceReply response) {
        List<String> cookies = connection.getHeaderFields().get("Set-Cookie");
        if (!CollectionUtils.isEmpty(cookies)) {
            String cookie = cookies.get(0);
            response.setCookie(cookie);
        }
    }

    private URLConnection sendXML(final PaymentService paymentService,
                                  final MerchantInfo merchantInfo,
                                  final String cookie) throws WorldpayCommunicationException, WorldpayModelTransformationException {
        final URLConnection con;
        try {
            final String environment = configurationService.getConfiguration().getString(WORLDPAY_CONFIG_ENVIRONMENT);
            final String endpoint = configurationService.getConfiguration().getString(WORLDPAY_CONFIG_ENDPOINT + "." + environment);

            final URL url = new URL(endpoint);
            con = url.openConnection();

            con.setDoOutput(true);
            con.setUseCaches(false);
            byte[] loginPassword = (merchantInfo.getMerchantCode() + ":" + merchantInfo.getMerchantPassword()).getBytes(StandardCharsets.UTF_8);
            con.setRequestProperty("Authorization", "Basic " + new String(Base64.getEncoder().encode(loginPassword), StandardCharsets.UTF_8));
            con.setRequestProperty("Host", url.getHost());

            if (con instanceof HttpURLConnection) {
                final HttpURLConnection httpCon = (HttpURLConnection) con;
                httpCon.setRequestMethod("POST");
                if (cookie != null) {
                    httpCon.setRequestProperty("Cookie", cookie);
                }
            }
            try (final XmlStreamWriter streamWriter = new XmlStreamWriter(con.getOutputStream(), StandardCharsets.UTF_8.name())) {
                final Marshaller marshaller = WorldpayConstants.JAXB_CONTEXT.createMarshaller();
                marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
                streamWriter.write(WorldpayConstants.XML_HEADER);
                marshaller.marshal(paymentService, streamWriter);
            }
        } catch (MalformedURLException e) {
            throw new WorldpayCommunicationException("Worldpay URL is incorrect", e);
        } catch (IOException e) {
            throw new WorldpayCommunicationException("Unable to initiate communication with Worldpay", e);
        } catch (JAXBException e) {
            throw new WorldpayModelTransformationException("XML context or marshalling failure while sending message to Worldpay", e);
        }
        return con;
    }

    @Override
    public void logXMLOut(final Marshaller marshaller, final PaymentService paymentService) {
        try {
            LOG.info("*** XML OUT ***");
            final StringWriter stringWriter = new StringWriter();
            marshaller.marshal(paymentService, stringWriter);
            LOG.info(stringWriter.toString());
            LOG.info("*** XML OUT END ***");
        } catch (JAXBException e) {
            LOG.debug("There was an error marshalling the paymentService for debug logging", e);
        }
    }

    @Required
    public void setPaymentServiceMarshaller(final PaymentServiceMarshaller paymentServiceMarshaller) {
        this.paymentServiceMarshaller = paymentServiceMarshaller;
    }

    @Required
    public void setConfigurationService(final ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }
}
