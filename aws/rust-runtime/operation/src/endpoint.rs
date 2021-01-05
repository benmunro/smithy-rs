/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

use crate::middleware::OperationMiddleware;
use crate::Operation;
use core::convert::AsRef;
use http::uri::Uri;
use std::error::Error;
use std::str::FromStr;

pub struct StaticEndpoint(http::Uri);

impl StaticEndpoint {
    pub fn uri(&self) -> &Uri {
        &self.0
    }
    pub fn from_service_region(svc: impl AsRef<str>, region: impl AsRef<str>) -> Self {
        StaticEndpoint(
            Uri::from_str(&format!(
                "https://{}.{}.amazonaws.com",
                svc.as_ref(),
                region.as_ref()
            ))
            .unwrap(),
        )
    }

    pub fn from_uri(uri: Uri) -> Self {
        StaticEndpoint(uri)
    }

    pub fn apply(&self, base_uri: &Uri) -> Uri {
        let parts = self.0.clone().into_parts();

        Uri::builder()
            .authority(parts.authority.expect("base uri must have an authority"))
            .scheme(parts.scheme.expect("base uri must have scheme"))
            .path_and_query(base_uri.path_and_query().unwrap().clone())
            .build()
            .expect("valid uri")
    }
}

pub trait ProvideEndpoint {
    fn set_endpoint(&self, request_uri: &mut Uri);
}

impl ProvideEndpoint for StaticEndpoint {
    fn set_endpoint(&self, request_uri: &mut Uri) {
        let new_uri = self.apply(request_uri);
        *request_uri = new_uri;
    }
}

impl<H, T> OperationMiddleware<H> for T
where
    T: ProvideEndpoint,
{
    fn apply(&self, request: &mut Operation<H>) -> Result<(), Box<dyn Error>> {
        self.set_endpoint(&mut request.base.uri_mut());
        Ok(())
    }
}

// TODO: this should probably move to a collection of middlewares
#[derive(Clone, Copy)]
/// Set the endpoint for a request based on the endpoint config
pub struct EndpointMiddleware;
impl<H> OperationMiddleware<H> for EndpointMiddleware {
    fn apply(&self, request: &mut Operation<H>) -> Result<(), Box<dyn Error>> {
        let endpoint_config = &request.endpoint_config;
        endpoint_config.set_endpoint(&mut request.base.uri_mut());
        Ok(())
    }
}

#[cfg(test)]
mod test {
    use crate::endpoint::StaticEndpoint;
    use http::Uri;
    use std::str::FromStr;

    #[test]
    fn endpoint_from_svc() {
        let endpoint = StaticEndpoint::from_service_region("dynamodb", "us-west-2");
        assert_eq!(
            endpoint.uri().to_string(),
            "https://dynamodb.us-west-2.amazonaws.com/"
        );
    }

    #[test]
    fn properly_update_uri() {
        let uri = Uri::builder()
            .path_and_query("/get?k=123&v=456")
            .build()
            .unwrap();
        let endpoint = StaticEndpoint::from_uri(Uri::from_str("http://localhost:8080/").unwrap());
        assert_eq!(
            endpoint.apply(&uri).to_string(),
            "http://localhost:8080/get?k=123&v=456"
        );
    }
}
