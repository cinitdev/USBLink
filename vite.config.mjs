import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  clearScreen: false,
  optimizeDeps: {
    include: ["react", "react-dom/client"],
  },
  server: {
    port: 5173,
    strictPort: true,
    warmup: {
      clientFiles: ["./src/main.jsx"],
    },
  },
  build: {
    target: "es2021",
  },
  plugins: [react()],
});
