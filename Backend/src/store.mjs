import fs from "node:fs/promises";
import path from "node:path";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isUuid(value) {
  return typeof value === "string" && UUID.test(value);
}

async function writeAtomic(file, data) {
  const temp = `${file}.${process.pid}.tmp`;
  await fs.writeFile(temp, data);
  await fs.rename(temp, file);
}

// Lux3D result URLs expire after two hours, so every generated model is copied here.
export function createStore(dataDir) {
  const modelsDir = path.join(dataDir, "models");
  const jobsDir = path.join(dataDir, "jobs");

  return {
    async init() {
      await fs.mkdir(modelsDir, { recursive: true });
      await fs.mkdir(jobsDir, { recursive: true });
    },

    modelPath(id) {
      if (!isUuid(id)) throw new Error("invalid model id");
      return path.join(modelsDir, `${id}.glb`);
    },

    async saveModel(id, buffer) {
      await writeAtomic(this.modelPath(id), buffer);
    },

    async saveJob(job) {
      if (!isUuid(job.id)) throw new Error("invalid job id");
      await writeAtomic(path.join(jobsDir, `${job.id}.json`), JSON.stringify(job, null, 2));
    },

    async loadJobs() {
      const jobs = [];
      for (const name of await fs.readdir(jobsDir)) {
        if (!name.endsWith(".json")) continue;
        try {
          jobs.push(JSON.parse(await fs.readFile(path.join(jobsDir, name), "utf8")));
        } catch {
          // A corrupt record should not stop the server from booting.
        }
      }
      return jobs;
    },
  };
}
