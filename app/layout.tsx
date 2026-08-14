import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Thrive — Save smarter. Eat better.",
  description: "Family savings, affordable recipes, pantry planning, and grocery budgeting in one simple app.",
  openGraph: {
    title: "Thrive — Save smarter. Eat better.",
    description: "Save on groceries, plan affordable meals, and stay on budget.",
    images: [{ url: "/og.png", width: 1200, height: 630, alt: "Thrive grocery savings app" }],
  },
  twitter: { card: "summary_large_image", images: ["/og.png"] },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
