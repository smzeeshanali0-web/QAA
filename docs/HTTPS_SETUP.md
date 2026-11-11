# HTTPS setup (zaroori — warna keys/passwords plain text mein jate hain)

App ab `http://` ko **block** karti hai (sirf ghar/LAN/Tailscale ke private address allowed). Public server ke liye HTTPS lagayen.

## Sabse aasaan (Docker + Caddy, free certificate)
1. Ek domain/subdomain lein (free: duckdns.org) aur uska A-record apne server ke public IP par rakhein.
2. Server par ports **80, 443 (TCP)** aur **10000-20000 (UDP)** kholen.
3. Repo ka folder server par rakhein, phir:
   ```bash
   cp ai-backend/.env.example ai-backend/.env     # API_KEY (lamba random), VOICE_PROVIDER, ELEVENLABS_API_KEY
   DOMAIN=voice.example.com docker compose up -d --build
   ```
4. App -> Settings -> Backend URL: `https://voice.example.com` -> Save -> **Test connection**.

## Sirf testing ke liye
Settings mein **Allow plain http://** ON karein. Is mein API key, SIP password, ElevenLabs key bina encryption ke network par jate hain — production mein kabhi nahi.

## API key
`API_KEY` kam az kam 32 random characters rakhein (`openssl rand -hex 24`).
