// ─────────────────────────────────────────────────────────────────────────────
// seedPosts.js
// Seeds the Firestore `posts` collection with realistic sample data.
//
// BEFORE RUNNING:
//   1. Download your service account key from Firebase Console →
//      Project Settings → Service accounts → Generate new private key
//   2. Save the file as:  scripts/serviceAccountKey.json
//   3. Edit the SEED_CONFIG section below (set authorUid, authorUsername, etc.)
//   4. Run: npm install && npm run seed:posts
// ─────────────────────────────────────────────────────────────────────────────

import { initializeApp, cert } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { createRequire } from "module";
import { randomUUID } from "crypto";

const require = createRequire(import.meta.url);
const serviceAccount = require("./serviceAccountKey.json");

// ─── SEED CONFIG ──────────────────────────────────────────────────────────────
// ⚠️  Fill these in before running.
//     authorUid must match a real document ID in your `users` Firestore collection.
const SEED_CONFIG = {
  authorUid: "utTF7z2Cymc61FYLFjvqWCn2Uuv2",        // e.g. "abc123xyz"
  authorUsername: "tensyano67",   // e.g. "shane"
  authorProfileImageUrl: "",
  numberOfPosts: 30,
};
// ─────────────────────────────────────────────────────────────────────────────

initializeApp({
  credential: cert(serviceAccount),
});

const db = getFirestore();

// Picsum image IDs to cycle through (feel free to add more)
const PICSUM_IDS = [10, 20, 26, 37, 42, 58, 65, 100, 119, 160, 200, 250, 300, 350, 400, 450, 500, 550, 600, 650];

const CAPTIONS = [
  "Chasing sunsets and good vibes 🌅",
  "Sometimes the best therapy is a long walk and fresh air 🌿",
  "Life is too short for boring coffee ☕",
  "The mountains are calling and I must go 🏔️",
  "Found my happy place 🌊",
  "Good food, good mood 🍜",
  "Not all those who wander are lost ✈️",
  "Small moments, big memories 📸",
  "City lights and late nights 🌃",
  "Bloom where you are planted 🌸",
  "Saturday mornings hit different when you slow down ☀️",
  "Exploring hidden gems one step at a time 🗺️",
  "Grateful for the simple things in life 🙏",
  "Art is everywhere if you look close enough 🎨",
  "New week, new energy, same dreams 💫",
];

function getImageUrl(index) {
  const id = PICSUM_IDS[index % PICSUM_IDS.length];
  return `https://picsum.photos/id/${id}/800/600`;
}

function getTimestamp(postsAgo) {
  // Space posts ~2 hours apart so the feed has a natural timeline
  const now = Date.now();
  return now - postsAgo * 2 * 60 * 60 * 1000;
}

async function seedPosts() {
  const { authorUid, authorUsername, authorProfileImageUrl, numberOfPosts } = SEED_CONFIG;

  if (authorUid === "REPLACE_WITH_REAL_UID") {
    console.error("❌  Please set authorUid in SEED_CONFIG before running.");
    process.exit(1);
  }

  console.log(`\n🌱  Seeding ${numberOfPosts} posts for @${authorUsername} (uid: ${authorUid})\n`);

  const batch = db.batch();
  const postsRef = db.collection("posts");
  const usersRef = db.collection("users");

  for (let i = 0; i < numberOfPosts; i++) {
    const postId = randomUUID();
    const imageUrl = getImageUrl(i);
    const caption = CAPTIONS[i % CAPTIONS.length];
    const createdAt = getTimestamp(i);

    const post = {
      postId,
      authorUid,
      authorUsername,
      authorProfileImageUrl,
      mediaUrls: [imageUrl],
      mediaTypes: ["image"],
      caption,
      likeCount: 0,
      commentCount: 0,
      createdAt,
    };

    batch.set(postsRef.doc(postId), post);
    console.log(`  📝  [${i + 1}/${numberOfPosts}] ${caption.substring(0, 40)}…`);
    console.log(`       Image: ${imageUrl}`);
    console.log(`       postId: ${postId}\n`);
  }

  // Increment the user's postCount
  batch.update(usersRef.doc(authorUid), {
    postCount: FieldValue.increment(numberOfPosts),
  });

  await batch.commit();

  console.log(`✅  Done! ${numberOfPosts} posts written to Firestore.\n`);
}

seedPosts().catch((err) => {
  console.error("❌  Seed failed:", err.message);
  process.exit(1);
});
