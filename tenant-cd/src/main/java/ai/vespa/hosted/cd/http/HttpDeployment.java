package ai.vespa.hosted.cd.http;

import ai.vespa.hosted.cd.Deployment;
import ai.vespa.hosted.cd.Endpoint;
import ai.vespa.hosted.cd.TestDeployment;
import ai.vespa.hosted.cd.TestEndpoint;

/**
 * A remote deployment of a Vespa application, reachable over HTTP. Contains {@link HttpEndpoint}s.
 *
 * @author jonmv
 */
public class HttpDeployment implements Deployment {

    @Override
    public Endpoint endpoint() {
        return null;
    }

    @Override
    public Endpoint endpoint(String id) {
        return null;
    }

    @Override
    public TestDeployment asTestDeployment() {
        return null;
    }

}