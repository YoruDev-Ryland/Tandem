using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace EmuSync.Server.Data.Migrations.Sqlite
{
    /// <inheritdoc />
    public partial class AddBackupRuns : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "BackupRuns",
                columns: table => new
                {
                    Id = table.Column<long>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    Utc = table.Column<long>(type: "INTEGER", nullable: false),
                    Ok = table.Column<bool>(type: "INTEGER", nullable: false),
                    Bytes = table.Column<long>(type: "INTEGER", nullable: false),
                    Name = table.Column<string>(type: "TEXT", maxLength: 64, nullable: true),
                    Error = table.Column<string>(type: "TEXT", maxLength: 1000, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_BackupRuns", x => x.Id);
                });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "BackupRuns");
        }
    }
}
