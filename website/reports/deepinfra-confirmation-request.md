# DeepInfra account-specific confirmation request

Draft for the owner to send; not sent automatically. No API keys or reading text
should be attached. Identity details remain deferred in the website drafts.

To: policy@deepinfra.com

Subject: Parrot recap integration — permitted use, DPA and EEA transfers

Hello,

I operate Parrot, a reading app based in Slovenia. Its optional, invitation-only
Cloud feature lets users request short summaries of their own reading sessions.
The service is free during early access and may become commercial later.

The backend uses your standard OpenAI-compatible chat-completions API with
`mistralai/Mistral-Nemo-Instruct-2407`. Users do not receive API keys or direct API
access. We send selected reading text, including potentially personal information,
only when users enable recaps. We do not request training, fine-tuning, bulk
inference, or third-party Google/Anthropic models.

Please confirm:

1. This customer-facing integration is permitted on my self-serve API account,
   including under Terms §11(a)(viii), both for free early access and a future
   commercial offering. Is a separate Service Order or written permission needed?
2. How I can execute your GDPR Article 28 data processing agreement, including
   technical/organizational measures and the applicable subprocessor terms.
3. Processing locations for this model and the applicable EEA transfer mechanism
   (for example SCCs, their modules/annexes and relevant transfer information).
4. Whether Terms §7(b)'s zero-content-retention commitment and no-training
   restrictions apply to this exact endpoint, model and account, and how its
   support, legal, security and abuse exceptions operate in practice.
5. Categories and retention periods of non-content request/debugging, security,
   abuse and billing records, and whether end-user IPs or the request's `user`
   identifier are included in those records.
6. How deletion/rights requests, incidents and subprocessor-change notices are
   handled for data processed through this API.

Please provide the relevant agreement documents and any account-specific steps.

Thank you.

## Why this confirmation is still required

Official terms, modified August 17, 2026 and checked October 7, 2026:
https://deepinfra.com/terms

- §5(a) refers to a DPA **if executed**; this is not evidence one has been signed.
- §7(b) commits to zero content retention but has support, operational metadata,
  legal, fraud, security and abuse exceptions.
- §11(a)(viii) restricts third-party availability absent express permission. Its
  application to an embedded recap feature requires confirmation, not assumption.

Official inference privacy documentation:
https://docs.deepinfra.com/account/data-privacy

Model licence and working API access are not substitutes for the processor
agreement, transfer safeguards or account-specific permitted-use confirmation.
