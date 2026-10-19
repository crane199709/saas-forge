#!/bin/sh
set -eu

root_access=$(cat /run/secrets/root-access-key)
root_secret=$(cat /run/secrets/root-secret-key)
brand_access=$(cat /run/secrets/brand-access-key)
brand_secret=$(cat /run/secrets/brand-secret-key)
test -n "$root_access" && test -n "$root_secret" && test -n "$brand_access" && test -n "$brand_secret"

attempt=0
until mc alias set brand-storage http://brand-storage:9000 "$root_access" "$root_secret" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    test "$attempt" -lt 30 || exit 1
    sleep 2
done

mc mb --ignore-existing brand-storage/sf-brand-assets
cat > /tmp/brand-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
    "Resource": ["arn:aws:s3:::sf-brand-assets/*"]
  }]
}
EOF
mc admin policy create brand-storage sf-brand-assets /tmp/brand-policy.json
mc admin user add brand-storage "$brand_access" "$brand_secret"
mc admin policy attach brand-storage sf-brand-assets --user "$brand_access"
mc anonymous set none brand-storage/sf-brand-assets
