# Tesla USB live stream discussion brief

## User question
- Tesla의 USB에 하드웨어를 추가해서, 차 안에서 찍히는 영상을 블루투스 또는 다른 원격 기술로 휴대폰으로 수시로 넘길 수 있는지 검토

## Verified findings so far
1. Tesla dashcam footage is stored on a connected USB drive.
2. Tesla app Dashcam Viewer streams Dashcam/Sentry footage from the vehicle to the phone, not from cloud storage.
3. The app feature requires MCU2 (Intel) or higher and Premium Connectivity.
4. Viewing limits apply (around 15 min or 1 hour depending on region).
5. TeslaUsb / teslausb shows a common DIY pattern: Raspberry Pi or other SBC emulates a USB drive for Tesla, then copies recordings off to another archive and offers a web UI to view/download.
6. TesClip and Perception are third-party Tesla dashcam viewers.
7. Search snippets / community docs suggest Tesla USB ports are for storage/media/dashcam recording, not direct camera input.

## Decision needed
Please assess: 
- Is the user's exact idea feasible?
- What products already exist?
- What do DIY users commonly do?
- If the direct Tesla-USB-camera route is not realistic, what architecture should be used instead?

## Output format
Answer in Korean with concise bullets and a recommended build stack.
