#!/bin/sh
# Run against the final signed .app, including after exporting/re-signing an archive.
set -eu
if [ "$#" -ne 1 ] || [ ! -d "$1" ]; then
    echo "Usage: /bin/sh verify-signing.sh /path/to/TeamTalk.app" >&2
    exit 2
fi
app_path=$1
entitlements_file=$(mktemp -t teamtalk-ios-entitlements)
trap 'rm -f "$entitlements_file"' EXIT HUP INT TERM
/usr/bin/codesign --verify --deep --strict "$app_path"
/usr/bin/codesign --display --entitlements - --xml "$app_path" > "$entitlements_file"
signed_environment=$(/usr/libexec/PlistBuddy -c 'Print :aps-environment' "$entitlements_file")
declared_environment=$(/usr/libexec/PlistBuddy -c 'Print :TeamTalkPushEnvironment' "$app_path/Info.plist")
case "$signed_environment" in development|production) ;; *) echo "Invalid APNs entitlement" >&2; exit 1 ;; esac
if [ "$signed_environment" != "$declared_environment" ]; then
    echo "APNs signing environment differs from the app's registration environment; rebuild with APNS_ENVIRONMENT=$signed_environment" >&2
    exit 1
fi
bundle_id=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_path/Info.plist")
application_id=$(/usr/libexec/PlistBuddy -c 'Print :application-identifier' "$entitlements_file")
case "$application_id" in
    *."$bundle_id") ;;
    *) echo "Signed application identifier differs from the app bundle identifier" >&2; exit 1 ;;
esac
echo "iOS signature, bundle identity and APNs environment agree ($signed_environment)."
