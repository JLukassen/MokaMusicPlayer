# Voluntary support for Moka Music Player

Moka is independently developed. Donations are optional and should never be necessary to use the app, obtain playback quality, or receive normal updates. Do **not** publish a funding link before the developer has created and verified the destination.

## Recommended: Ko-fi (simple one-time tips)

1. Visit https://ko-fi.com/ and create a creator account.
2. Choose a clear public page name, e.g. *Moka Music Player* (availability is not guaranteed), and add the Moka logo, a short description and a link to this repository.
3. In Ko-fi's **Payment Settings**, connect your own **Stripe** or **PayPal** account. Verify the account before sharing a page. Review applicable processor fees, optional Ko-fi service fees and any tax obligations.
4. Set the page focus to **Tips/Donations**. Consider one-time tips first; enable monthly tips or memberships only if you actually want to offer ongoing perks.
5. Open the resulting public URL in a private/incognito browser window. Verify the correct creator name and that payment options appear; do not send any test card details to collaborators.
6. Add the verified page URL to README's **Support Moka development** section and create `.github/FUNDING.yml` so GitHub displays a **Sponsor** button once that file exists on the default branch.

### GitHub Sponsor button

When a **real** Ko-fi username exists, create a repository-root file at `.github/FUNDING.yml`:

```yaml
ko_fi: YOUR_ACTUAL_KOFI_USERNAME
```

Replace `YOUR_ACTUAL_KOFI_USERNAME` with the username from your own Ko-fi page. This is a template only: do not commit it with a placeholder username. In GitHub, verify **Repository → Settings → General → Features → Sponsorships** is enabled. GitHub reads the funding file from the repository's **default branch**.

Alternatively, for a verified direct donation page URL:

```yaml
custom: ["https://your-verified-donation-page.example"]
```

Again, use the **real** public URL before committing. The example URL above is not an active payment page.

## Alternative: GitHub Sponsors

If eligible, visit https://github.com/sponsors and start creator onboarding. Complete the required account, payout and tax verification. Once a functioning sponsor profile is available, add the real GitHub handle:

```yaml
github: JLukassen
```

Do not enable or advertise the above entry unless the GitHub Sponsors page actually accepts contributions. Ko-fi and GitHub Sponsors can coexist in the same `FUNDING.yml` once both are operational.

## Suggested Ko-fi description

> Moka Music Player is an independently developed high-fidelity Android music player focused on lossless playback, powerful real-time DSP, an organized local library, and optional Navidrome streaming. If you enjoy the project and want to support ongoing development, you can leave a voluntary tip here. Moka's playback features and regular updates don't require a donation. Thank you for helping sustain the work!

## Google Play considerations

Keep donation calls to action in the GitHub repository or other off-app project pages initially. Linking from a Play-distributed app to outside payment mechanisms can raise Google Play Payments policy questions; do not add a donation payment link, WebView checkout, or payment SDK *inside the Android app* without checking the then-current Play policy and regional requirements. Donations are not a substitute for Play Billing for digital in-app purchases or feature unlocks.

**Official setup documentation:**
- Ko-fi: https://help.ko-fi.com/hc/en-us/articles/360014098514-Getting-started-on-Ko-fi
- GitHub: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/displaying-a-sponsor-button-in-your-repository
- Google Play Payments policy: https://support.google.com/googleplay/android-developer/answer/10281818
