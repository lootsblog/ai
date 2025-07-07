aws elbv2 describe-load-balancer-attributes --load-balancer-arn <your-alb-arn> | grep -i timeout

# Look specifically for:
# routing.http.request_timeout.timeout_seconds
# This is different from idle timeout and might be set to 120 seconds
