# Third-party source

`hongguo.cjs` derives from `spider/js/红果果[短].js` from
https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
retrieved on 2026-09-21. Upstream license: GPL-3.0; see LICENSE.drpys.
The filename was changed for local loading. The original header and comments remain.
The source is loaded only by the Node content service, not bundled into the TV app.
The service adapter and HTTP layer are original project code.

**Local modification (`requestVideoModel`)**: the upstream file requests the
`multi_video_model` endpoint with a signed request against a single host. The
signed request is rejected by the endpoint (HTTP 200 with `Code 110001`), so no
playback address can be obtained. The local patch keeps the signed call only as
a last resort and first tries unsigned requests with per-request random
`device_id`/`iid` values across multiple endpoint hosts, which returns
`video_model` reliably. All other upstream logic (signing helpers, stream
resolution, CENC handling) is unchanged and still used as fallback.

The Android/tvOS project skeleton is derived from
@react-native-tvos/template-tv 0.81.0-0 (MIT); see LICENSE.template-tv.
