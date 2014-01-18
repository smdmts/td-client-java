package com.treasure_data.newclient.http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;

import com.treasure_data.newclient.Configuration;
import com.treasure_data.newclient.Request;
import com.treasure_data.newclient.TreasureDataClientException;
import com.treasure_data.newclient.TreasureDataServiceException;
import com.treasure_data.newclient.TreasureDataServiceResponse;
import com.treasure_data.newclient.util.CountingInputStream;

public class TreasureDataHttpClient implements Closeable {

    private static final Logger LOG = Logger.getLogger(TreasureDataHttpClient.class.getName());

    private static HttpRequestFactory httpRequestFactory = new HttpRequestFactory();
    private static HttpClientFactory httpClientFactory = new HttpClientFactory();

    static {
        
    }

    private final HttpClient httpClient;
    private Configuration conf;

    public TreasureDataHttpClient(Configuration conf) throws TreasureDataClientException {
        this.conf = conf;
        this.httpClient = httpClientFactory.createHttpClient(conf);
    }

    public <M, REQ> M execute(
            Request<REQ> request,
            HttpResponseHandler<TreasureDataServiceResponse<M>> responseHandler,
            HttpResponseHandler<TreasureDataServiceResponse<TreasureDataServiceException>> errorResponseHandler,
            ExecutionContext context)
                    throws TreasureDataClientException, TreasureDataServiceException {
        // null check
        if (request == null
                || responseHandler == null
                || errorResponseHandler == null
                || context == null) {
            RuntimeException e = new NullPointerException(
                    "Internal Error: some of arguments are null.");
            LOG.log(Level.SEVERE, e.getMessage(), e);
            throw e;
        }

        return executeHelper(request, responseHandler, errorResponseHandler, context);
    }

    private <M, REQ> M executeHelper(
            Request<REQ> request,
            HttpResponseHandler<TreasureDataServiceResponse<M>> responseHandler,
            HttpResponseHandler<TreasureDataServiceResponse<TreasureDataServiceException>> errorResponseHandler,
            ExecutionContext context)
                    throws TreasureDataClientException, TreasureDataServiceException {
        int retryCount = 0;
        URI redirectedURI = null;
        HttpEntity entity = null;
        TreasureDataServiceException exception = null;

        // Make a copy of the original request params and headers so that we can
        // permute it in this loop and start over with the original every time.
        Map<String, String> originalParameters = new HashMap<String, String>();
        originalParameters.putAll(request.getParameters());

        Map<String, String> originalHeaders = new HashMap<String, String>();
        originalHeaders.putAll(request.getHeaders());

        while (true) {
            if (retryCount > 0) {
                request.setParameters(originalParameters);
                request.setHeaders(originalHeaders);
            }

            HttpRequestBase httpRequest = null;
            org.apache.http.HttpResponse httpResponse = null;

            try {
                // sign the rquest if a signer was provided
                if (context.getSinger() != null && context.getCredentials() != null) {
                    context.getSinger().sign(request, context.getCredentials());
                }

                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine(String.format("Send the request %s (retryCount=%d)",
                            request, retryCount));
                }

                httpRequest = httpRequestFactory.createHttpRequest(request, conf, entity, context);

                if (redirectedURI != null) {
                    httpRequest.setURI(redirectedURI);
                }

                // pause exponentially
                if (retryCount > 0) {
                    pauseExponentially(retryCount);
                }
                
                exception = null;
                httpResponse = httpClient.execute(httpRequest);

                if (isRequestSuccessful(httpResponse)) {
                    return handleResponse(request, responseHandler,
                            httpRequest, httpResponse, context);
                } else {
                    exception = handleErrorResponse(request, errorResponseHandler,
                            httpRequest, httpResponse, context);

                    if (!shouldRetry(httpRequest, exception, retryCount)) {
                        throw exception;
                    }

                    resetRequestAfterError(request, exception);
                }
            } catch (IOException e) {
                LOG.log(Level.INFO, "Unable to execute HTTP request: " + e.getMessage(), e);

                if (!shouldRetry(httpRequest, e, retryCount)) {
                    TreasureDataClientException ex = new TreasureDataClientException(
                            "Unable to execute HTTP request: " + e.getMessage(), e);
                    LOG.log(Level.SEVERE, ex.getMessage(), ex);
                    throw ex;
                }

                resetRequestAfterError(request, e);
            } finally {
                retryCount++;

                try {
                    httpResponse.getEntity().getContent().close();
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "during close method", t);
                }
            }
        }
    }

    private boolean isRequestSuccessful(org.apache.http.HttpResponse response) {
        int status = response.getStatusLine().getStatusCode();
        return status / 100 == HttpStatus.SC_OK / 100;
    }

    private void pauseExponentially(final int retryCount) throws TreasureDataClientException {
        long scaleFactor = 300;
        long delay = (long) (Math.pow(2, retryCount) * scaleFactor);
        delay = Math.min(delay, Configuration.MAX_BACKOFF_IN_MILLISECONDS);

        LOG.fine(String.format("will retry in %dms, attempt number: %d",
                delay, retryCount));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            throw new TreasureDataClientException(e.getMessage(), e);
        }
    }

    private <M, REQ> M handleResponse(
            Request<REQ> request,
            HttpResponseHandler<TreasureDataServiceResponse<M>> responseHandler,
            HttpRequestBase httpRequest,
            org.apache.http.HttpResponse apacheHttpResponse,
            ExecutionContext context)
                    throws IOException, TreasureDataClientException {
        HttpResponse httpResponse = createResponse(request, httpRequest, apacheHttpResponse);
        try {
            CountingInputStream countingInputStream =
                    new CountingInputStream(httpResponse.getContent());
            httpResponse.setContent(countingInputStream);
            TreasureDataServiceResponse<M> response = responseHandler.handle(httpResponse);
            return response.getResult();
        } catch (Exception e) {
            String errorMessage = "Unable to unmarshall response (" + e.getMessage() + ")";
            throw new TreasureDataClientException(errorMessage, e);
        }
    }

    private <REQ> TreasureDataServiceException handleErrorResponse(
            Request<REQ> request,
            HttpResponseHandler<TreasureDataServiceResponse<TreasureDataServiceException>> errorResponseHandler,
            HttpRequestBase httpRequest,
            org.apache.http.HttpResponse apacheHttpResponse,
            ExecutionContext context)
                    throws IOException, TreasureDataServiceException {
        HttpResponse httpResponse = createResponse(request, httpRequest, apacheHttpResponse);
        StatusLine stat = apacheHttpResponse.getStatusLine();
        int status = stat.getStatusCode();

        TreasureDataServiceException exception = null;
        try {
            exception = errorResponseHandler.handle(httpResponse).getResult();
            exception.setStatusCode(status);
            exception.setErrorCode(stat.getReasonPhrase());
            LOG.fine("Received error response: " + exception.toString());
        } catch (Exception e) {
            // If the errorResponseHandler doesn't work, then check for error
            // responses that don't have any content
            if (status == 413) {
                exception = new TreasureDataServiceException("Request entity too large");
                exception.setStatusCode(413);
                exception.setErrorCode("Request entity too large");
            } else if (status == 503 && "Service Unavailable".equalsIgnoreCase(apacheHttpResponse.getStatusLine().getReasonPhrase())) {
                exception = new TreasureDataServiceException("Service unavailable");
                exception.setStatusCode(503);
                exception.setErrorCode("Service unavailable");
            } else {
                String errorMessage = "Unable to unmarshall error response (" + e.getMessage() + ")";
                throw new TreasureDataServiceException(errorMessage, e);
            }
        }

        exception.setStatusCode(status);
        exception.fillInStackTrace();
        return exception;
    }

    private <REQ> HttpResponse createResponse(
            Request<REQ> request,
            HttpRequestBase method,
            org.apache.http.HttpResponse apacheHttpResponse) throws IOException {
        HttpResponse httpResponse = new HttpResponse(request, method);

        if (apacheHttpResponse.getEntity() != null) {
            httpResponse.setContent(apacheHttpResponse.getEntity().getContent());
        }

        httpResponse.setStatusCode(apacheHttpResponse.getStatusLine().getStatusCode());
        httpResponse.setStatusText(apacheHttpResponse.getStatusLine().getReasonPhrase());
        for (Header header : apacheHttpResponse.getAllHeaders()) {
            httpResponse.addHeader(header.getName(), header.getValue());
        }

        return httpResponse;
    }

    public void close() {
        if (httpClient != null) {
            httpClient.getConnectionManager().shutdown();
        }
    }

    private boolean shouldRetry(HttpRequestBase method, Exception exception, int retries) {
        if (retries >= 10) {
            return false;
        }

        if (method instanceof HttpEntityEnclosingRequest) {
            HttpEntity entity = ((HttpEntityEnclosingRequest)method).getEntity();
            if (entity != null && !entity.isRepeatable()) {
                LOG.fine("Entity not repeatable");
                return false;
            }
        }

        if (exception instanceof IOException) {
            LOG.fine("Retrying on " + exception.getClass().getName()
                        + ": " + exception.getMessage());
            return true;
        }

        if (exception instanceof TreasureDataServiceException) {
            TreasureDataServiceException ase = (TreasureDataServiceException)exception;

            /*
             * For 500 internal server errors and 503 service
             * unavailable errors, we want to retry, but we need to use
             * an exponential back-off strategy so that we don't overload
             * a server with a flood of retries. If we've surpassed our
             * retry limit we handle the error response as a non-retryable
             * error and go ahead and throw it back to the user as an exception.
             */
            if (ase.getStatusCode() == HttpStatus.SC_INTERNAL_SERVER_ERROR
                || ase.getStatusCode() == HttpStatus.SC_SERVICE_UNAVAILABLE) {
                return true;
            }
        }

        return false;
    }

    private void resetRequestAfterError(Request<?> request, Exception cause) throws TreasureDataClientException {
        if (request.getContent() != null && request.getContent().markSupported())  {
            try {
                request.getContent().reset();
            } catch (IOException e) {
                // This exception comes from being unable to reset the input stream,
                // so throw the original, more meaningful exception
                throw new TreasureDataClientException(
                        "Encountered an exception and couldn't reset the stream to retry", cause);
            }
        }
    }

}